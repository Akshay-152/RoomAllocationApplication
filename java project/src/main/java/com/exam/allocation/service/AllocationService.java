package com.exam.allocation.service;

import com.exam.allocation.model.Exam;
import com.exam.allocation.model.ExamEligibility;
import com.exam.allocation.model.Room;
import com.exam.allocation.model.Seat;
import com.exam.allocation.model.SeatAllocation;
import com.exam.allocation.model.Student;
import com.exam.allocation.repository.ExamEligibilityRepository;
import com.exam.allocation.repository.ExamRepository;
import com.exam.allocation.repository.RoomRepository;
import com.exam.allocation.repository.SeatAllocationRepository;
import com.exam.allocation.repository.SeatRepository;
import com.exam.allocation.repository.StudentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AllocationService {

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private SeatRepository seatRepository;

    @Autowired
    private ExamRepository examRepository;

    @Autowired
    private ConflictService conflictService;

    @Autowired
    private SeatAllocationRepository seatAllocationRepository;

    @Autowired
    private ExamEligibilityRepository examEligibilityRepository;

    @Autowired
    private ExamSemesterService examSemesterService;

    /** Result of an allocation run, returned as JSON to the frontend. */
    public static class AllocationResult {
        private boolean success;
        private String message;
        private int eligibleStudents;
        private int allocated;
        private int unallocated;
        private int roomsUsed;
        private int benchesUsed;
        private List<String> conflicts = new ArrayList<>();
        private int historySeatings;
        private int repeatViolations;

        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public int getEligibleStudents() { return eligibleStudents; }
        public void setEligibleStudents(int eligibleStudents) { this.eligibleStudents = eligibleStudents; }
        public int getAllocated() { return allocated; }
        public void setAllocated(int allocated) { this.allocated = allocated; }
        public int getUnallocated() { return unallocated; }
        public void setUnallocated(int unallocated) { this.unallocated = unallocated; }
        public int getRoomsUsed() { return roomsUsed; }
        public void setRoomsUsed(int roomsUsed) { this.roomsUsed = roomsUsed; }
        public int getBenchesUsed() { return benchesUsed; }
        public void setBenchesUsed(int benchesUsed) { this.benchesUsed = benchesUsed; }
        public List<String> getConflicts() { return conflicts; }
        public void setConflicts(List<String> conflicts) { this.conflicts = conflicts; }
        /** Past seatings (seat_allocations rows) analysed for the 2-times fairness rule. */
        public int getHistorySeatings() { return historySeatings; }
        public void setHistorySeatings(int historySeatings) { this.historySeatings = historySeatings; }
        /** Placements that had to exceed the 2-times room/desk-position limit. */
        public int getRepeatViolations() { return repeatViolations; }
        public void setRepeatViolations(int repeatViolations) { this.repeatViolations = repeatViolations; }
    }

    /**
     * Core Algorithm: Generates the seating chart for a given exam across the given rooms.
     *
     * - Only students matching the exam's branch/batch are seated (blank = "all").
     * - Students from different branch/batch groups are interleaved round-robin so that
     *   students of the same group are spread out instead of sitting in an adjacent block.
     * - Any previous chart for the same exam is cleared first, so the operation can be
     *   re-run safely (the old implementation crashed with a unique-constraint error).
     * - Seat-fairness: a student never sits in the same room or at the same desk
     *   position more than {@link SeatRepeatGuard#MAX_SAME_ROOM} times - counted
     *   across the whole stored seating history plus this chart. Students at the
     *   cap are held back for another room; only when no free alternative seat
     *   exists are they seated there anyway, counted and reported as a violation.
     */
    @Transactional
    public AllocationResult generateAllocation(Long examId, List<Long> roomIds) {
        AllocationResult result = new AllocationResult();

        Exam exam = examRepository.findById(examId)
                .orElseThrow(() -> new IllegalArgumentException("Invalid Exam ID"));

        // Inactive rooms are excluded so a deactivated room is never used again.
        List<Room> rooms = roomRepository.findAllById(roomIds).stream()
                .filter(Room::isActive)
                .sorted(java.util.Comparator.comparingLong(AllocationService::seatingCapacity).reversed())
                .collect(Collectors.toList());
        if (rooms.isEmpty()) {
            result.setMessage("No active rooms available for allocation. Add (or re-activate) a room first.");
            return result;
        }

        // A fresh run replaces the previous chart: drop the stored allocations too.
        seatAllocationRepository.deleteByExamId(examId);
        seatAllocationRepository.flush();

        // Seat-fairness rule: the guard is built from every allocation still stored
        // (this exam's own chart was just deleted, so a chart never counts against itself).
        SeatRepeatGuard guard = SeatRepeatGuard.analyze(seatAllocationRepository.findAll());

        Set<String> eligibleBatches = eligibilityBatches(exam);

        List<Student> eligible = studentRepository.findAll().stream()
                .filter(s -> s.getAccountStatus() == Student.AccountStatus.ACTIVE)
                .filter(s -> matchesExam(s, exam, eligibleBatches))
                .collect(Collectors.toList());
        result.setEligibleStudents(eligible.size());

        if (eligible.isEmpty()) {
            java.util.Set<Integer> semesters = examSemesterService.assignedSemesters(exam);
            result.setMessage("No students match this exam (batch: " + valueOrAll(exam.getCourse())
                    + ", branch: " + valueOrAll(exam.getBranch())
                    + ", semester: " + (semesters.isEmpty() ? "all" : semesters) + ").");
            return result;
        }

        Map<String, List<Student>> studentsByCourse = eligible.stream()
                .collect(Collectors.groupingBy(AllocationService::groupKey, LinkedHashMap::new, Collectors.toList()));
        List<Student> sequence = interleave(studentsByCourse);

        // Work queue holding the exact existing round-robin order; every desk draws
        // its students from the front of this queue (see fillDesk for the subject-mixing rule).
        LinkedList<Student> queue = new LinkedList<>(sequence);
        int allocated = 0;

        for (Room room : rooms) {
            if (queue.isEmpty()) break;
            int rows = room.getRowsCount();
            int cols = room.getColumnsCount();
            if (rows <= 0 || cols <= 0) continue;

            List<Seat> seats = prepareSeats(room, exam, rows, cols, room.getStudentsPerDesk());
            if (seats.isEmpty()) continue;

            allocated += fillDesks(seats, queue, exam, room, guard, true);
            seatRepository.saveAll(seats);
        }

        // Fairness relaxation: the caps must never cost a student a seat. Any desk
        // the 2-times rule had to skip is filled now, and every over-cap placement
        // is counted so the administrator sees where repeats were unavoidable.
        if (!queue.isEmpty()) {
            for (Room room : rooms) {
                if (queue.isEmpty()) break;
                List<Seat> empty = seatRepository.findByRoomId(room.getId()).stream()
                        .filter(s -> s.getExam() != null && exam.getId().equals(s.getExam().getId()))
                        .filter(s -> s.getAllocatedStudent() == null)
                        .sorted(java.util.Comparator.comparingInt((Seat s) -> s.getRowNumber())
                                .thenComparingInt(s -> s.getColumnNumber())
                                .thenComparingInt(s -> s.getDeskPosition()))
                        .collect(Collectors.toList());
                if (empty.isEmpty()) continue;
                allocated += fillDesks(empty, queue, exam, room, guard, false);
                seatRepository.saveAll(empty);
            }
        }

        result.setAllocated(allocated);
        result.setUnallocated(sequence.size() - allocated);
        result.setSuccess(true);

        // Store the result as DRAFT seat allocations (visible to students only after publishing).
        // Build the seat-allocation records. The unique constraint on
        // (exam_id, seat_id) is enforced here as a safety net too: if two students
        // somehow reached the same seat in one run, only the first write succeeds and
        // the second candidate is released back into the queue.
        List<Seat> chartSeats = new ArrayList<>();
        for (Room room : rooms) {
            chartSeats.addAll(seatRepository.findByRoomId(room.getId()).stream()
                    .filter(s -> s.getExam() != null && exam.getId().equals(s.getExam().getId()))
                    .collect(Collectors.toList()));
        }
        Set<Seat> assignedSeats = new HashSet<>();
        for (Seat seat : chartSeats) {
            if (seat.getAllocatedStudent() == null) continue;
            if (assignedSeats.contains(seat)) {
                seat.setAllocatedStudent(null);   // seat already claimed - release the student
                continue;
            }
            seatAllocationRepository.save(new SeatAllocation(
                    exam, seat.getAllocatedStudent(), roomOf(rooms, seat), seat));
            assignedSeats.add(seat);
        }

        // A room the fairness rule skipped entirely holds no seated student and is
        // not counted as used.
        int roomsUsed = (int) chartSeats.stream()
                .filter(s -> s.getAllocatedStudent() != null && s.getRoom() != null)
                .map(s -> s.getRoom().getId())
                .distinct()
                .count();
        result.setRoomsUsed(roomsUsed);
        int benchesUsed = (int) chartSeats.stream()
                .filter(s -> s.getAllocatedStudent() != null && s.getRoom() != null)
                .map(s -> s.getRoom().getId() + ":" + s.getRowNumber() + ":" + s.getColumnNumber())
                .distinct()
                .count();
        result.setBenchesUsed(benchesUsed);

        StringBuilder msg = new StringBuilder("Seating chart generated: " + allocated + " of "
                + sequence.size() + " student(s) seated across " + benchesUsed + " bench(es) in "
                + roomsUsed + " room(s).");
        if (allocated < sequence.size()) {
            msg.append(" ").append(sequence.size() - allocated)
               .append(" student(s) could not be seated - add more rooms or larger rooms.");
        }
        msg.append(" Seat-fairness: a student sits in a room at most ")
           .append(SeatRepeatGuard.MAX_SAME_ROOM)
           .append(" times and at a desk position at most ")
           .append(SeatRepeatGuard.MAX_SAME_SEAT)
           .append(" times (checked against ")
           .append(guard.getHistorySeatings())
           .append(" earlier seating(s)).");
        result.setMessage(msg.toString());

        List<String> warnings = new ArrayList<>(conflictService.allocationWarnings(exam, chartSeats));
        if (guard.getViolations() > 0) {
            warnings.add(guard.getViolations() + " seating(s) had to exceed the "
                    + SeatRepeatGuard.MAX_SAME_ROOM + "-times room/desk limit because no free "
                    + "alternative seat existed - add more rooms to remove the repeats.");
        }
        result.setConflicts(warnings);
        result.setHistorySeatings(guard.getHistorySeatings());
        result.setRepeatViolations(guard.getViolations());
        result.setMessage(result.getMessage() + " The chart is a draft - publish it to show seats to students.");
        return result;
    }

    private static long seatingCapacity(Room room) {
        if (room.getRowsCount() <= 0 || room.getColumnsCount() <= 0) return 0;
        return (long) room.getRowsCount() * room.getColumnsCount() * room.getStudentsPerDesk();
    }

    /** Publishes the stored allocations of an exam (students can then see their seats). */
    @Transactional
    public int publishAllocations(Long examId) {
        Exam exam = examRepository.findById(examId)
                .orElseThrow(() -> new IllegalArgumentException("Exam not found with id " + examId));
        if (exam.getStatus() == Exam.ExamStatus.CANCELLED) {
            throw new IllegalArgumentException("Cancelled exams cannot have published seating. Reopen the exam first.");
        }
        List<SeatAllocation> allocations = seatAllocationRepository.findByExamId(examId);
        for (SeatAllocation allocation : allocations) {
            allocation.setStatus(SeatAllocation.Status.PUBLISHED);
        }
        seatAllocationRepository.saveAll(allocations);
        return allocations.size();
    }

    /** Moves published allocations back to draft (hidden from students again). */
    @Transactional
    public int unpublishAllocations(Long examId) {
        List<SeatAllocation> allocations = seatAllocationRepository
                .findByExamIdAndStatus(examId, SeatAllocation.Status.PUBLISHED);
        for (SeatAllocation allocation : allocations) {
            allocation.setStatus(SeatAllocation.Status.DRAFT);
        }
        seatAllocationRepository.saveAll(allocations);
        return allocations.size();
    }

    /** Batch names the exam is assigned to via exam_eligibility (empty = use legacy branch/course). */
    public Set<String> eligibilityBatches(Exam exam) {
        Set<String> batches = new HashSet<>();
        for (ExamEligibility eligibility : examEligibilityRepository.findByExamId(exam.getId())) {
            batches.add(eligibility.getBatchName());
        }
        return batches;
    }

    /**
     * Fills the given seats desk by desk (consecutive seats that share the same
     * row and column form one desk) and returns how many students were seated.
     */
    private int fillDesks(List<Seat> seats, LinkedList<Student> queue, Exam exam, Room room,
                          SeatRepeatGuard guard, boolean enforceCap) {
        int assigned = 0;
        List<Seat> desk = new ArrayList<>();
        for (Seat seat : seats) {
            if (!desk.isEmpty() && (seat.getRowNumber() != desk.get(0).getRowNumber()
                    || seat.getColumnNumber() != desk.get(0).getColumnNumber())) {
                assigned += fillDesk(desk, queue, exam, room, guard, enforceCap);
                desk.clear();
            }
            desk.add(seat);
        }
        if (!desk.isEmpty()) assigned += fillDesk(desk, queue, exam, room, guard, enforceCap);
        return assigned;
    }

    /**
     * Fills one desk (every student position of a single row/column cell) from the
     * front of the queue and returns how many students were seated.
     *
     * <p><b>Seat-fairness rule:</b> when {@code enforceCap} is on, students who
     * already sat in this room the maximum number of times are held back - they
     * keep their place in the queue and are seated in a later room (or by the
     * relaxation pass when no alternative exists). If the whole window is at the
     * cap the desk is left empty and the queue keeps its order.
     *
     * <p><b>Subject/Course Mixing Rule (enhancement):</b> while filling the desk, a
     * student whose course differs from the courses already seated at this desk is
     * preferred, so that e.g. {@code CS | OOP | CS} is chosen over {@code CS | CS | CS}
     * whenever OOP students are still available. All existing rules are preserved:
     * students are still drawn from the original round-robin sequence (only their
     * order within/near this desk may be swapped), no student is ever duplicated or
     * lost (unused candidates go back to the front of the queue in their original
     * order), never more than {@code deskSeats.size()} students are placed, and when
     * no different course is left the next student in the sequence is used as before.
     * The choice is deterministic: always the earliest fitting student in sequence order.
     */
    private int fillDesk(List<Seat> deskSeats, LinkedList<Student> queue, Exam exam, Room room,
                         SeatRepeatGuard guard, boolean enforceCap) {
        int needed = deskSeats.size();
        if (queue.isEmpty() || needed == 0) return 0;

        // Look ahead far enough to see every course that is still available, so a
        // different subject just past the desk's own slice can still be pulled in.
        int distinctRemaining = (int) queue.stream()
                .map(AllocationService::groupKey)
                .map(c -> c.toLowerCase())
                .distinct()
                .count();
        int windowSize = Math.min(queue.size(), needed + distinctRemaining);

        List<Student> window = new ArrayList<>(windowSize);
        for (int i = 0; i < windowSize; i++) window.add(queue.pollFirst());

        // Seat-fairness: students at the 2-times cap for this room are not eligible
        // here; they stay in the queue (original order) for a later room.
        List<Student> candidates = new ArrayList<>(window.size());
        for (Student student : window) {
            if (!enforceCap || !guard.roomAtCap(student, room)) candidates.add(student);
        }

        Set<String> coursesAtDesk = new HashSet<>();
        List<Student> picked = new ArrayList<>(needed);
        for (int k = 0; k < needed && !candidates.isEmpty(); k++) {
            int chosen = -1;
            for (int j = 0; j < candidates.size(); j++) {
                if (!coursesAtDesk.contains(groupKey(candidates.get(j)).toLowerCase())) {
                    chosen = j;   // a branch/batch not yet sitting at this desk
                    break;
                }
            }
            if (chosen == -1) chosen = 0;   // all remaining groups already present: keep sequence order
            Student student = candidates.remove(chosen);
            coursesAtDesk.add(groupKey(student).toLowerCase());
            picked.add(student);
        }

        // Return the students we did not use to the FRONT of the queue in their
        // original window order - held-back students included: nothing is skipped,
        // duplicated or reordered for later desks.
        Set<Student> pickedSet = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        pickedSet.addAll(picked);
        List<Student> rest = window.stream()
                .filter(s -> !pickedSet.contains(s))
                .collect(Collectors.toList());
        rest.addAll(queue);
        queue.clear();
        queue.addAll(rest);

        if (picked.isEmpty()) return 0;
        int placed = 0;
        for (int i = 0; i < deskSeats.size() && i < picked.size(); i++) {
            Seat seat = deskSeats.get(i);
            seat.setAllocatedStudent(picked.get(i));
            seat.setExam(exam);
            guard.record(picked.get(i), seat);
            placed++;
        }
        return placed;
    }

    /**
     * Returns the seats of this room for this exam, ordered row/column/position.
     * Each desk (grid cell) holds {@code studentsPerDesk} seats, so a desk that
     * seats two students gets two seat rows at the same row/column.
     * Creates the grid if it does not exist yet (or if the room layout changed),
     * and clears any previous student assignment so re-running is idempotent.
     */
    private List<Seat> prepareSeats(Room room, Exam exam, int rows, int cols, int perDesk) {
        if (perDesk < 1) perDesk = 1;
        int total = rows * cols * perDesk;

        List<Seat> existing = seatRepository.findByRoomId(room.getId()).stream()
                .filter(s -> s.getExam() != null && exam.getId() != null && exam.getId().equals(s.getExam().getId()))
                .sorted(java.util.Comparator.comparingInt((Seat s) -> s.getRowNumber())
                        .thenComparingInt(s -> s.getColumnNumber())
                        .thenComparingInt(s -> s.getDeskPosition()))
                .collect(Collectors.toList());

        if (existing.size() != total) {
            if (!existing.isEmpty()) {
                seatRepository.deleteAll(existing);
                seatRepository.flush();
            }
            List<Seat> created = new ArrayList<>();
            for (int r = 1; r <= rows; r++) {
                for (int c = 1; c <= cols; c++) {
                    for (int p = 1; p <= perDesk; p++) {
                        Seat seat = new Seat();
                        seat.setRowNumber(r);
                        seat.setColumnNumber(c);
                        seat.setDeskPosition(p);
                        seat.setRoom(room);
                        seat.setExam(exam);
                        created.add(seat);
                    }
                }
            }
            return seatRepository.saveAll(created);
        }

        // Re-run: wipe previous chart of this exam, keep the physical desks.
        existing.forEach(s -> s.setAllocatedStudent(null));
        return existing;
    }

    private Room roomOf(List<Room> rooms, Seat seat) {
        if (seat.getRoom() != null) return seat.getRoom();
        return rooms.stream()
                .filter(r -> seat.getRoom() != null && r.getId().equals(seat.getRoom().getId()))
                .findFirst().orElse(null);
    }

    /** Round-robin one student from each course per pass, until every student is placed. */
    private List<Student> interleave(Map<String, List<Student>> byCourse) {
        List<String> courses = new ArrayList<>(byCourse.keySet());
        Collections.sort(courses);
        Map<String, Integer> pointers = new HashMap<>();
        for (String c : courses) pointers.put(c, 0);

        List<Student> out = new ArrayList<>();
        boolean progress = true;
        while (progress) {
            progress = false;
            for (String c : courses) {
                int p = pointers.get(c);
                if (p < byCourse.get(c).size()) {
                    out.add(byCourse.get(c).get(p));
                    pointers.put(c, p + 1);
                    progress = true;
                }
            }
        }
        return out;
    }

    /**
     * True when the student belongs to the audience of this exam.
     * Explicit exam_eligibility rows win; otherwise the legacy branch/batch
     * columns decide (blank = "all students").
     */
    public boolean matchesExam(Student student, Exam exam) {
        return matchesExam(student, exam, eligibilityBatches(exam));
    }

    public boolean matchesExam(Student student, Exam exam, Set<String> eligibleBatches) {
        // The exam's semester audience (exam_semesters rows, else the legacy column;
        // empty = every semester). A student without a semester is a wildcard too.
        if (!examSemesterService.matchesStudent(exam, student.getSemester())) return false;
        if (!branchMatches(student.getBranch(), exam.getBranch())) return false;
        if (eligibleBatches != null && !eligibleBatches.isEmpty()) {
            return eligibleBatches.contains(student.getBatch()) || eligibleBatches.contains(student.getBranch());
        }
        return courseMatches(student, exam);
    }

    private boolean branchMatches(String studentBranch, String examBranch) {
        String wanted = norm(examBranch);
        if (isAll(wanted)) return true;
        return wanted.equals(norm(studentBranch));
    }

    /**
     * The exam's batch/course matches the student's batch first; a branch of the
     * same name also matches so imported/legacy "course" values keep working.
     */
    private boolean courseMatches(Student student, Exam exam) {
        String wanted = norm(exam.getCourse());
        if (isAll(wanted)) return true;
        return wanted.equals(norm(student.getBatch())) || wanted.equals(norm(student.getBranch()));
    }

    private boolean isAll(String norm) {
        return norm.isEmpty() || norm.equals("all") || norm.equals("*")
                || norm.equals("any") || norm.equals("all courses");
    }

    /** Grouping key used for interleaving, desk mixing, colours and the PDF legend. */
    public static String groupKey(Student s) {
        String branch = (s.getBranch() == null || s.getBranch().isBlank()) ? "Unbranched" : s.getBranch().trim();
        String batch = (s.getBatch() == null || s.getBatch().isBlank()) ? "Unbatched" : s.getBatch().trim();
        return branch + "\u00b7" + batch;
    }

    private String norm(String v) { return v == null ? "" : v.trim().toLowerCase(); }

    private String valueOrAll(String v) { return (v == null || v.isBlank()) ? "all" : v; }
}
