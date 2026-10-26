package com.exam.allocation.controller;

import com.exam.allocation.model.*;
import com.exam.allocation.repository.*;
import com.exam.allocation.service.AllocationService;
import com.exam.allocation.service.AuditService;
import com.exam.allocation.service.ExamNotificationService;
import com.exam.allocation.service.ExamSemesterService;
import com.exam.allocation.service.NotificationService;
import com.exam.allocation.service.UserAccountService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Administrator endpoints for students, rooms, seating allocation and allocation
 * publishing. All of these require the {@code ROLE_ADMIN} role (see SecurityConfig).
 */
@RestController
@RequestMapping("/api")
public class AllocationController {

    @Autowired private StudentRepository studentRepository;
    @Autowired private RoomRepository roomRepository;
    @Autowired private ExamRepository examRepository;
    @Autowired private SeatRepository seatRepository;
    @Autowired private SeatAllocationRepository seatAllocationRepository;
    @Autowired private ExamRoomRepository examRoomRepository;
    @Autowired private AllocationService allocationService;
    @Autowired private ExamNotificationService examNotificationService;
    @Autowired private NotificationService notificationService;
    @Autowired private UserAccountService userAccountService;
    @Autowired private AuditService auditService;

    // --- Student Endpoints ---

    /** Students, optionally filtered by ?search=, ?branch=, ?batch=, ?semester=, ?status=. */
    @GetMapping("/students")
    public List<Student> getAllStudents(@RequestParam(required = false) String search,
                                        @RequestParam(required = false) String branch,
                                        @RequestParam(required = false) String batch,
                                        @RequestParam(required = false) Integer semester,
                                        @RequestParam(required = false) String status) {
        List<Student> students = studentRepository.findAll();
        String needle = search == null ? "" : search.trim().toLowerCase();
        return students.stream()
                .filter(s -> needle.isEmpty()
                        || contains(s.getRollNo(), needle) || contains(s.getName(), needle))
                .filter(s -> branch == null || branch.isBlank() || branch.equalsIgnoreCase(branchOf(s)))
                .filter(s -> batch == null || batch.isBlank() || batch.equalsIgnoreCase(s.getBatch()))
                .filter(s -> semester == null || semester.equals(s.getSemester()))
                .filter(s -> status == null || status.isBlank()
                        || status.equalsIgnoreCase(s.getAccountStatus().name()))
                .sorted(Comparator.comparing(s -> s.getRollNo() == null ? "" : s.getRollNo()))
                .collect(Collectors.toList());
    }

    @PostMapping("/students")
    public Student addStudent(@RequestBody Student student) {
        ensureRollNoFree(student.getRollNo(), null);
        ExamSemesterService.validateSemester(student.getSemester());
        if (student.getAccountStatus() == null) student.setAccountStatus(Student.AccountStatus.ACTIVE);
        if (student.getDepartment() == null || student.getDepartment().isBlank()) {
            student.setDepartment(student.getBranch());
        }
        Student saved = studentRepository.save(student);
        // An administrator adding a student automatically enables that student's login.
        userAccountService.createStudentAccount(saved);
        auditService.record(actor(), "STUDENT_CREATED", "student:" + saved.getId(),
                "Roll no " + saved.getRollNo() + " created with a login account.");
        return saved;
    }

    @PutMapping("/students/{id}")
    public Student updateStudent(@PathVariable Long id, @RequestBody Student student) {
        Student existing = studentRepository.findById(java.util.Objects.requireNonNull(id))
                .orElseThrow(() -> new IllegalArgumentException("Student not found with id " + id));
        ensureRollNoFree(student.getRollNo(), id);
        ExamSemesterService.validateSemester(student.getSemester());
        String previousRollNo = existing.getRollNo();
        existing.setRollNo(student.getRollNo());
        existing.setName(student.getName());
        existing.setBranch(student.getBranch());
        existing.setBatch(student.getBatch());
        existing.setDepartment(student.getDepartment());
        existing.setSemester(student.getSemester());
        existing.setEmail(student.getEmail());
        existing.setPhone(student.getPhone());
        existing.setAccountStatus(student.getAccountStatus() == null
                ? Student.AccountStatus.ACTIVE : student.getAccountStatus());
        Student saved = studentRepository.save(existing);
        if (previousRollNo != null && !previousRollNo.equals(saved.getRollNo())) {
            userAccountService.deleteAccountFor(previousRollNo);
        }
        userAccountService.createStudentAccount(saved);
        userAccountService.syncStudentStatus(saved);
        auditService.record(actor(), "STUDENT_UPDATED", "student:" + saved.getId(),
                "Roll no " + saved.getRollNo() + " updated.");
        return saved;
    }

    /** Deletes a student together with the login account and any seat references. */
    @DeleteMapping("/students/{id}")
    public Map<String, Object> deleteStudent(@PathVariable Long id) {
        Student student = studentRepository.findById(java.util.Objects.requireNonNull(id))
                .orElseThrow(() -> new IllegalArgumentException("Student not found with id " + id));
        List<Seat> seated = seatRepository.findByAllocatedStudentId(id);
        seated.forEach(seat -> seat.setAllocatedStudent(null));
        seatRepository.saveAll(seated);
        seatAllocationRepository.findByStudentId(id).forEach(seatAllocationRepository::delete);
        // Notification history and device registrations follow the student.
        notificationService.deleteForStudent(id);
        userAccountService.deleteAccountFor(student.getRollNo());
        studentRepository.deleteById(java.util.Objects.requireNonNull(id));
        auditService.record(actor(), "STUDENT_DELETED", "student:" + id,
                "Roll no " + student.getRollNo() + " deleted.");
        return Map.of("deleted", true, "clearedSeats", seated.size());
    }

    /** Administrator password reset for a student. */
    @PostMapping("/students/{id}/reset-password")
    public Map<String, Object> resetPassword(@PathVariable Long id, @RequestBody Map<String, String> body) {
        Student student = studentRepository.findById(java.util.Objects.requireNonNull(id))
                .orElseThrow(() -> new IllegalArgumentException("Student not found with id " + id));
        String newPassword = body.get("password");
        if (newPassword == null || newPassword.length() < 4) {
            throw new IllegalArgumentException("The reset password must be at least 4 characters long.");
        }
        userAccountService.resetStudentPassword(student.getRollNo(), newPassword);
        auditService.record(actor(), "STUDENT_PASSWORD_RESET", "student:" + id,
                "Password reset for roll no " + student.getRollNo() + ".");
        return Map.of("reset", true, "message", "Password reset. The student signs in with the new password.");
    }

    /** Enables or disables a student account (a disabled student cannot sign in). */
    @PostMapping("/students/{id}/status")
    public Map<String, Object> setStudentStatus(@PathVariable Long id, @RequestBody Map<String, String> body) {
        Student student = studentRepository.findById(java.util.Objects.requireNonNull(id))
                .orElseThrow(() -> new IllegalArgumentException("Student not found with id " + id));
        String status = body.getOrDefault("status", "");
        if (!status.equalsIgnoreCase("ACTIVE") && !status.equalsIgnoreCase("INACTIVE")) {
            throw new IllegalArgumentException("Status must be ACTIVE or INACTIVE.");
        }
        student.setAccountStatus(Student.AccountStatus.valueOf(status.toUpperCase()));
        studentRepository.save(student);
        userAccountService.syncStudentStatus(student);
        auditService.record(actor(), "STUDENT_STATUS", "student:" + id,
                "Roll no " + student.getRollNo() + " set to " + student.getAccountStatus() + ".");
        return Map.of("status", student.getAccountStatus().name());
    }

    // --- Room Endpoints ---

    @GetMapping("/rooms")
    public List<Room> getAllRooms(@RequestParam(required = false) Boolean active) {
        List<Room> rooms = roomRepository.findAll();
        if (active != null) rooms = rooms.stream().filter(r -> r.isActive() == active).collect(Collectors.toList());
        return rooms;
    }

    @PostMapping("/rooms")
    public Room addRoom(@RequestBody Room room) {
        ensureRoomNumberFree(room.getRoomNumber(), null);
        Room saved = roomRepository.save(room);
        auditService.record(actor(), "ROOM_CREATED", "room:" + saved.getId(), saved.getDisplayName());
        return saved;
    }

    @PutMapping("/rooms/{id}")
    public Room updateRoom(@PathVariable Long id, @RequestBody Room room) {
        Room existing = roomRepository.findById(java.util.Objects.requireNonNull(id))
                .orElseThrow(() -> new IllegalArgumentException("Room not found with id " + id));
        ensureRoomNumberFree(room.getRoomNumber(), id);
        String previousNumber = existing.getRoomNumber();
        String previousBuilding = existing.getBuilding();
        Object previousFloor = existing.getFloor();
        existing.setRoomNumber(room.getRoomNumber());
        existing.setName(room.getName());
        existing.setCapacity(room.getCapacity());
        existing.setRowsCount(room.getRowsCount());
        existing.setColumnsCount(room.getColumnsCount());
        existing.setStudentsPerDesk(room.getStudentsPerDesk());
        existing.setBuilding(room.getBuilding());
        existing.setFloor(room.getFloor());
        existing.setActive(room.isActive());
        boolean roomDetailsChanged = !sameText(previousNumber, room.getRoomNumber())
                || !sameText(previousBuilding, room.getBuilding())
                || !java.util.Objects.equals(previousFloor, room.getFloor());
        Room saved = roomRepository.save(existing);
        if (roomDetailsChanged) {
            // Only students seated in this room for a published exam hear about it.
            examNotificationService.roomChanged(saved,
                    seatAllocationRepository.findByRoomIdAndStatus(id, SeatAllocation.Status.PUBLISHED));
        }
        auditService.record(actor(), "ROOM_UPDATED", "room:" + saved.getId(), saved.getDisplayName());
        return saved;
    }

    /** Deactivates (or re-activates) a room; inactive rooms are never used for new allocations. */
    @PostMapping("/rooms/{id}/status")
    public Map<String, Object> setRoomStatus(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Room room = roomRepository.findById(java.util.Objects.requireNonNull(id))
                .orElseThrow(() -> new IllegalArgumentException("Room not found with id " + id));
        boolean active = Boolean.parseBoolean(String.valueOf(body.getOrDefault("active", "true")));
        room.setActive(active);
        roomRepository.save(room);
        auditService.record(actor(), "ROOM_STATUS", "room:" + id, room.getDisplayName() + " -> " + (active ? "ACTIVE" : "INACTIVE"));
        return Map.of("active", room.isActive());
    }

    @DeleteMapping("/rooms/{id}")
    public Map<String, Object> deleteRoom(@PathVariable Long id) {
        if (!roomRepository.existsById(java.util.Objects.requireNonNull(id))) {
            throw new IllegalArgumentException("Room not found with id " + id);
        }
        examRoomRepository.deleteByRoomId(id);
        seatAllocationRepository.deleteByRoomId(id);
        seatRepository.deleteAll(java.util.Objects.requireNonNull(seatRepository.findByRoomId(id)));
        // All allocations under this room are now abandoned (explicit row deletion, not orphaned).
        roomRepository.deleteById(java.util.Objects.requireNonNull(id));
        auditService.record(actor(), "ROOM_DELETED", "room:" + id, "Room deleted; allocations/seats cleared.");
        return Map.of("deleted", true);
    }

    // --- Allocation Endpoints ---

    /**
     * Generates the seating chart for an exam.
     * Body: {@code {"examId": 1, "roomIds": [1,2]}} - roomIds defaults to every active room.
     */
    @PostMapping("/allocate")
    public AllocationService.AllocationResult generateAllocation(@RequestBody(required = false) Map<String, Object> body) {
        Long examId = null;
        if (body != null && body.get("examId") != null) {
            examId = Long.valueOf(String.valueOf(body.get("examId")));
        }

        if (examId == null) {
            List<Exam> exams = examRepository.findAll();
            if (exams.isEmpty()) {
                return failure("No exams found. Create an exam first, then generate the chart.");
            }
            examId = exams.stream().max(Comparator.comparing(exam -> exam.getId())).map(exam -> exam.getId()).orElse(null);
        }

        List<Long> roomIds;
        if (body != null && body.get("roomIds") instanceof List<?> list && !list.isEmpty()) {
            List<Long> requested = list.stream().map(v -> Long.valueOf(String.valueOf(v))).collect(Collectors.toList());
            // Only rooms that are marked available may be used for a new allocation.
            java.util.Set<Long> activeIds = roomRepository.findAll().stream()
                    .filter(room -> room != null && room.isActive())
                    .map(room -> room.getId())
                    .collect(Collectors.toSet());
            roomIds = requested.stream().filter(activeIds::contains).collect(Collectors.toList());
            if (roomIds.isEmpty()) {
                return failure("None of the selected rooms are available for allocation. Activate rooms in Room Management first.");
            }
        } else {
            roomIds = roomRepository.findAll().stream()
                    .filter(room -> room != null && room.isActive())
                    .map(room -> room.getId())
                    .collect(Collectors.toList());
        }
        if (roomIds.isEmpty()) {
            return failure("No active rooms available for allocation. Add a room first.");
        }

        AllocationService.AllocationResult result = allocationService.generateAllocation(examId, roomIds);
        if (result.isSuccess()) {
            rememberExamRooms(examId, roomIds);
            auditService.record(actor(), "ALLOCATION_GENERATED", "exam:" + examId,
                    result.getAllocated() + " seat(s) allocated (draft).");
        }
        return result;
    }

    /** Publishes a generated chart so students can see their seats. */
    @PostMapping("/allocations/{examId}/publish")
    public Map<String, Object> publishAllocations(@PathVariable Long examId) {
        int published = allocationService.publishAllocations(examId);
        Exam exam = examRepository.findById(java.util.Objects.requireNonNull(examId))
                .orElseThrow(() -> new IllegalArgumentException("Exam not found with id " + examId));
        // Notify each newly-(re)published student; a changed seat produces a
        // "seat changed" notification, an unchanged one is deduplicated.
        Map<String, Object> fanOut = examNotificationService.seatingPublished(exam,
                        seatAllocationRepository.findByExamIdAndStatus(examId, SeatAllocation.Status.PUBLISHED))
                .toMap();
        auditService.record(actor(), "ALLOCATION_PUBLISHED", "exam:" + examId, published + " allocation(s) published.");
        return Map.of("published", published, "notifications", fanOut,
                "message", "Seating published - " + published + " student(s) can now see their seat.");
    }

    /** Moves published allocations back to draft (hidden from students again). */
    @PostMapping("/allocations/{examId}/unpublish")
    public Map<String, Object> unpublishAllocations(@PathVariable Long examId) {
        int unpublished = allocationService.unpublishAllocations(examId);
        auditService.record(actor(), "ALLOCATION_UNPUBLISHED", "exam:" + examId, unpublished + " allocation(s) hidden.");
        return Map.of("unpublished", unpublished);
    }

    /** Stored allocations (admin view); filters: examId, studentId, status. */
    @GetMapping("/allocations")
    public List<Map<String, Object>> listAllocations(@RequestParam(required = false) Long examId,
                                                     @RequestParam(required = false) Long studentId,
                                                     @RequestParam(required = false) String status) {
        List<SeatAllocation> allocations = examId != null
                ? seatAllocationRepository.findByExamId(examId)
                : seatAllocationRepository.findAll();
        if (studentId != null) {
            allocations = allocations.stream()
                    .filter(a -> studentId.equals(a.getStudent().getId()))
                    .collect(Collectors.toList());
        }
        if (status != null && !status.isBlank()) {
            SeatAllocation.Status wanted = SeatAllocation.Status.valueOf(status.toUpperCase());
            allocations = allocations.stream().filter(a -> a.getStatus() == wanted).collect(Collectors.toList());
        }
        return allocations.stream()
                .sorted(Comparator.comparing((SeatAllocation a) -> a.getExam().getId())
                        .thenComparing(a -> a.getRoom().getId())
                        .thenComparingInt(a -> a.getSeat().getRowNumber())
                        .thenComparingInt(a -> a.getSeat().getColumnNumber()))
                .map(this::describe)
                .collect(Collectors.toList());
    }

    // --- Seat / Chart Endpoints ---

    /** All seats, or only the seats of one exam when ?examId= is given. */
    @GetMapping("/seats")
    public List<Seat> getAllSeats(@RequestParam(required = false) Long examId) {
        List<Seat> seats = examId != null
                ? seatRepository.findByExamId(examId)
                : seatRepository.findAll();
        seats.sort(Comparator
                .comparing((Seat s) -> s.getRoom() == null ? 0L : s.getRoom().getId())
                .thenComparingInt(seat -> seat.getRowNumber())
                .thenComparingInt(seat -> seat.getColumnNumber())
                .thenComparingInt(seat -> seat.getDeskPosition()));
        return seats;
    }

    // --- Helpers ---

    private Map<String, Object> describe(SeatAllocation allocation) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", allocation.getId());
        out.put("examId", allocation.getExam().getId());
        out.put("examName", allocation.getExam().getSubjectName());
        out.put("studentId", allocation.getStudent().getId());
        out.put("rollNo", allocation.getStudent().getRollNo());
        out.put("name", allocation.getStudent().getName());
        out.put("roomId", allocation.getRoom().getId());
        out.put("roomNumber", allocation.getRoom().getRoomNumber());
        out.put("building", allocation.getRoom().getBuilding());
        out.put("floor", allocation.getRoom().getFloor());
        out.put("row", allocation.getSeat().getRowNumber());
        out.put("column", allocation.getSeat().getColumnNumber());
        out.put("deskPosition", allocation.getSeat().getDeskPosition());
        out.put("seatCode", seatCode(allocation.getRoom(), allocation.getSeat()));
        out.put("status", allocation.getStatus().name());
        return out;
    }

    /** Human readable seat identifier, e.g. "R6-A01". */
    static String seatCode(Room room, Seat seat) {
        String prefix = room.getRoomNumber() == null ? "R" : ("R" + room.getRoomNumber().replaceAll("[^A-Za-z0-9]", ""));
        String row = String.valueOf((char) ('A' + seat.getRowNumber() - 1));
        String col = String.format("%02d", seat.getColumnNumber());
        String suffix = seat.getDeskPosition() > 1 ? String.valueOf((char) ('a' + seat.getDeskPosition() - 1)) : "";
        return prefix + "-" + row + col + suffix;
    }

    private void rememberExamRooms(Long examId, List<Long> roomIds) {
        Exam exam = examRepository.findById(java.util.Objects.requireNonNull(examId)).orElse(null);
        if (exam == null) return;
        examRoomRepository.deleteByExamId(examId);
        for (Room room : roomRepository.findAllById(java.util.Objects.requireNonNull(roomIds))) {
            examRoomRepository.save(new ExamRoom(exam, room));
        }
    }

    private String branchOf(Student student) {
        return student.getBranch();
    }

    private static boolean sameText(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private boolean contains(String value, String needle) {
        return value != null && value.toLowerCase().contains(needle);
    }

    private String actor() {
        return SecurityContextHolder.getContext().getAuthentication() == null
                ? "system" : SecurityContextHolder.getContext().getAuthentication().getName();
    }

    private AllocationService.AllocationResult failure(String message) {
        AllocationService.AllocationResult result = new AllocationService.AllocationResult();
        result.setSuccess(false);
        result.setMessage(message);
        return result;
    }

    private void ensureRollNoFree(String rollNo, Long ignoreId) {
        if (rollNo == null || rollNo.isBlank()) return;
        Student other = studentRepository.findByRollNo(rollNo);
        if (other != null && !other.getId().equals(ignoreId)) {
            throw new IllegalArgumentException("Roll No \"" + rollNo + "\" already exists.");
        }
    }

    private void ensureRoomNumberFree(String roomNumber, Long ignoreId) {
        if (roomNumber == null || roomNumber.isBlank()) return;
        Room other = roomRepository.findByRoomNumber(roomNumber);
        if (other != null && !other.getId().equals(ignoreId)) {
            throw new IllegalArgumentException("Room \"" + roomNumber + "\" already exists.");
        }
    }
}
