package com.exam.allocation.controller;

import com.exam.allocation.model.*;
import com.exam.allocation.repository.*;
import com.exam.allocation.service.AllocationService;
import com.exam.allocation.service.AuditService;
import com.exam.allocation.service.ExamSemesterService;
import com.exam.allocation.service.SessionService;
import com.exam.allocation.service.UserAccountService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Student self-service endpoints. Every response is scoped to the signed-in
 * student, so no student can read another student's records.
 */
@RestController
@RequestMapping("/api/student")
public class StudentSelfController {

    @Autowired private SessionService sessionService;
    @Autowired private StudentRepository studentRepository;
    @Autowired private ExamRepository examRepository;
    @Autowired private SeatAllocationRepository seatAllocationRepository;
    @Autowired private AnnouncementRepository announcementRepository;
    @Autowired private AllocationService allocationService;
    @Autowired private ExamSemesterService examSemesterService;
    @Autowired private UserAccountService userAccountService;
    @Autowired private AuditService auditService;

    /** The signed-in student's own profile. */
    @GetMapping("/me")
    public Map<String, Object> me() {
        Student student = requireStudent();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", student.getId());
        out.put("rollNo", student.getRollNo());
        out.put("name", student.getName());
        out.put("branch", student.getBranch());
        out.put("department", student.getDepartment());
        out.put("batch", student.getBatch());
        out.put("semester", student.getSemester());
        out.put("email", student.getEmail());
        out.put("phone", student.getPhone());
        out.put("accountStatus", student.getAccountStatus().name());
        return out;
    }

    /** Overview numbers for the student dashboard. */
    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        Student student = requireStudent();
        LocalDate today = LocalDate.now();
        List<Exam> exams = eligibleExams(student);
        // Cancelled exams are listed on "My Exams" (with their badge) but never
        // counted as upcoming/completed and never promoted as the next exam.
        long upcoming = exams.stream()
                .filter(e -> e.getStatus() != Exam.ExamStatus.CANCELLED)
                .filter(e -> e.getExamDate() != null && !e.getExamDate().isBefore(today)).count();
        long completed = exams.stream()
                .filter(e -> e.getStatus() != Exam.ExamStatus.CANCELLED)
                .filter(e -> e.getExamDate() != null && e.getExamDate().isBefore(today)).count();
        SeatAllocation latest = seatAllocationRepository.findByStudentIdOrderByCreatedAtDesc(student.getId()).stream()
                .filter(a -> a.getStatus() == SeatAllocation.Status.PUBLISHED)
                .findFirst().orElse(null);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rollNo", student.getRollNo());
        out.put("name", student.getName());
        out.put("branch", student.getBranch());
        out.put("batch", student.getBatch());
        out.put("semester", student.getSemester());
        out.put("upcomingExams", upcoming);
        out.put("completedExams", completed);
        out.put("nextExam", describeExam(nextExam(exams, today)));
        out.put("latestSeat", latest == null ? null : describeAllocation(latest));
        out.put("announcements", announcementsFor(student).stream().limit(5).map(this::describeAnnouncement).toList());
        return out;
    }

    /** Published exams the signed-in student is eligible for, with seat information when published. */
    @GetMapping("/exams")
    public List<Map<String, Object>> myExams() {
        Student student = requireStudent();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Exam exam : eligibleExams(student)) {
            Map<String, Object> entry = describeExam(exam);
            SeatAllocation allocation = seatAllocationRepository.findByExamIdAndStudentId(exam.getId(), student.getId());
            entry.put("seat", allocation != null && allocation.getStatus() == SeatAllocation.Status.PUBLISHED
                    ? describeAllocation(allocation) : null);
            out.add(entry);
        }
        return out;
    }

    /** The published seating of one exam (?examId=) or of the student's latest allocated exam. */
    @GetMapping("/seat")
    public Map<String, Object> mySeat(@RequestParam(required = false) Long examId) {
        Student student = requireStudent();
        List<SeatAllocation> published = seatAllocationRepository.findByStudentId(student.getId()).stream()
                .filter(a -> a.getStatus() == SeatAllocation.Status.PUBLISHED)
                .sorted(Comparator.comparing(a -> a.getExam().getExamDate(),
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();

        SeatAllocation found = null;
        if (examId != null) {
            found = published.stream().filter(a -> examId.equals(a.getExam().getId())).findFirst().orElse(null);
            if (found == null) {
                Exam exam = examRepository.findById(examId)
                        .orElseThrow(() -> new IllegalArgumentException("Exam not found with id " + examId));
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("exam", describeExam(exam));
                out.put("seat", null);
                out.put("message", "Your seat has not been allocated yet. Please check again later.");
                return out;
            }
        } else {
            found = published.isEmpty() ? null : published.get(0);
        }

        if (found == null) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("exam", null);
            out.put("seat", null);
            out.put("message", "No seating has been published for you yet. Please check again later.");
            return out;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("exam", describeExam(found.getExam()));
        out.put("seat", describeAllocation(found));
        return out;
    }

    /** Students may update their contact details only (no roll no, batch or seating). */
    @PutMapping("/profile")
    public Map<String, Object> updateProfile(@RequestBody Map<String, String> body) {
        Student student = requireStudent();
        String email = body.get("email");
        String phone = body.get("phone");
        if (email != null && !email.isBlank() && !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw new IllegalArgumentException("Please enter a valid email address.");
        }
        student.setEmail(blankToNull(email));
        student.setPhone(blankToNull(phone));
        studentRepository.save(student);
        auditService.record(student.getRollNo(), "PROFILE_UPDATED", "student:" + student.getId(),
                "Contact details updated by the student.");
        return Map.of("message", "Your profile has been updated.", "email", valueOrEmpty(student.getEmail()),
                "phone", valueOrEmpty(student.getPhone()));
    }

    /** Self-service password change (current password required). */
    @PostMapping("/password")
    public Map<String, Object> changePassword(@RequestBody Map<String, String> body) {
        Student student = requireStudent();
        userAccountService.changePassword(student.getRollNo(), body.get("currentPassword"), body.get("newPassword"));
        auditService.record(student.getRollNo(), "PASSWORD_CHANGE", "student:" + student.getId(),
                "Password changed by the student.");
        return Map.of("changed", true, "message", "Your password has been updated.");
    }

    // --- Helpers ---

    private List<Exam> eligibleExams(Student student) {
        return examRepository.findAll().stream()
                // Completed and cancelled exams stay visible on the student portal
                // (with their status badge) so the history is never hidden.
                .filter(e -> e.getStatus() == Exam.ExamStatus.PUBLISHED
                        || e.getStatus() == Exam.ExamStatus.COMPLETED
                        || e.getStatus() == Exam.ExamStatus.CANCELLED)
                // matchesExam enforces branch, batch AND the exam's semester audience,
                // so unrelated semesters never show up on the student portal.
                .filter(e -> allocationService.matchesExam(student, e))
                .sorted(Comparator.comparing(Exam::getExamDate, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    private Exam nextExam(List<Exam> exams, LocalDate today) {
        return exams.stream()
                .filter(e -> e.getStatus() != Exam.ExamStatus.CANCELLED)
                .filter(e -> e.getExamDate() != null && !e.getExamDate().isBefore(today))
                .findFirst().orElse(null);
    }

    /** @return the exam as JSON, or {@code null} when there is no upcoming exam yet. */
    private Map<String, Object> describeExam(Exam exam) {
        if (exam == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", exam.getId());
        out.put("subjectName", exam.getSubjectName());
        out.put("subjectCode", exam.getSubjectCode());
        out.put("examName", exam.getSubjectName());
        out.put("branch", exam.getBranch());
        out.put("course", exam.getCourse());
        out.put("examDate", exam.getExamDate() == null ? null : exam.getExamDate().toString());
        out.put("startTime", exam.getExamTime() == null ? null : exam.getExamTime().toString());
        out.put("endTime", exam.getEndTime() == null ? null : exam.getEndTime().toString());
        out.put("durationMinutes", exam.getDurationMinutes());
        out.put("semester", exam.getSemester());
        out.put("semesters", examSemesterService.assignedSemesters(exam).stream().sorted().toList());
        out.put("examType", exam.getExamType());
        out.put("instructions", exam.getInstructions());
        out.put("status", exam.getStatus().name());
        return out;
    }

    private Map<String, Object> describeAllocation(SeatAllocation allocation) {
        Map<String, Object> out = new LinkedHashMap<>();
        Room room = allocation.getRoom();
        Seat seat = allocation.getSeat();
        out.put("roomNumber", room.getRoomNumber());
        out.put("building", room.getBuilding());
        out.put("floor", room.getFloor());
        out.put("roomName", room.getDisplayName());
        out.put("row", seat.getRowNumber());
        out.put("column", seat.getColumnNumber());
        out.put("deskPosition", seat.getDeskPosition());
        out.put("seatCode", AllocationController.seatCode(room, seat));
        out.put("examId", allocation.getExam().getId());
        out.put("examName", allocation.getExam().getSubjectName());
        out.put("examDate", allocation.getExam().getExamDate() == null ? null : allocation.getExam().getExamDate().toString());
        out.put("startTime", allocation.getExam().getExamTime() == null ? null : allocation.getExam().getExamTime().toString());
        out.put("endTime", allocation.getExam().getEndTime() == null ? null : allocation.getExam().getEndTime().toString());
        out.put("instructions", allocation.getExam().getInstructions());
        return out;
    }

    private List<Announcement> announcementsFor(Student student) {
        return announcementRepository.findAllByOrderByCreatedAtDesc().stream()
                .filter(a -> a.getBatchName() == null || a.getBatchName().isBlank()
                        || a.getBatchName().equalsIgnoreCase(student.getBatch())
                        || a.getBatchName().equalsIgnoreCase(student.getBranch()))
                .filter(a -> a.getSemester() == null || a.getSemester().equals(student.getSemester()))
                .filter(a -> targetsRollNo(a, student.getRollNo()))
                .toList();
    }

    /** True when the announcement does not restrict recipients by roll number. */
    private boolean targetsRollNo(Announcement announcement, String rollNo) {
        String raw = announcement.getRollNos();
        if (raw == null || raw.isBlank() || rollNo == null) return true;
        for (String roll : raw.split(",")) {
            if (!roll.isBlank() && roll.trim().equalsIgnoreCase(rollNo)) return true;
        }
        return false;
    }

    private Map<String, Object> describeAnnouncement(Announcement announcement) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", announcement.getId());
        out.put("title", announcement.getTitle());
        out.put("body", announcement.getBody());
        out.put("createdAt", announcement.getCreatedAt().toString());
        return out;
    }

    /** Loaded inside a JPA session so the returned student is fully initialized. */
    private Student requireStudent() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return sessionService.requireStudent(username);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }
}
