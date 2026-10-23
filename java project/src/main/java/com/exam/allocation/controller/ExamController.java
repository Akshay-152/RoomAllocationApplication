package com.exam.allocation.controller;

import com.exam.allocation.model.Exam;
import com.exam.allocation.model.ExamEligibility;
import com.exam.allocation.model.SeatAllocation;
import com.exam.allocation.repository.ExamEligibilityRepository;
import com.exam.allocation.repository.ExamRepository;
import com.exam.allocation.repository.ExamRoomRepository;
import com.exam.allocation.repository.SeatAllocationRepository;
import com.exam.allocation.repository.SeatRepository;
import com.exam.allocation.service.AuditService;
import com.exam.allocation.service.ConflictService;
import com.exam.allocation.service.ExamNotificationService;
import com.exam.allocation.service.ExamSemesterService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Exam management endpoints: CRUD plus the publish/cancel lifecycle, eligibility
 * assignment and scheduling-conflict detection.
 *
 * <p>Access is restricted to administrators by
 * {@link com.exam.allocation.config.SecurityConfig} ({@code /api/exams/**} requires
 * {@code ROLE_ADMIN}), so the handlers below do not re-check the role.
 */
@RestController
@RequestMapping("/api/exams")
public class ExamController {

    @Autowired private ExamRepository examRepository;
    @Autowired private SeatRepository seatRepository;
    @Autowired private SeatAllocationRepository seatAllocationRepository;
    @Autowired private ExamEligibilityRepository eligibilityRepository;
    @Autowired private ExamRoomRepository examRoomRepository;
    @Autowired private ConflictService conflictService;
    @Autowired private ExamSemesterService examSemesterService;
    @Autowired private ExamNotificationService examNotificationService;
    @Autowired private AuditService auditService;

    /** All scheduling conflicts in the current exam schedule (same date+time+audience). */
    @GetMapping("/conflicts")
    public List<ConflictService.Conflict> getConflicts() {
        return conflictService.findConflicts();
    }

    @GetMapping
    public List<Exam> getAllExams(@RequestParam(required = false) String status) {
        List<Exam> exams = new ArrayList<>(examRepository.findAll());
        if (status != null && !status.isBlank()) {
            Exam.ExamStatus wanted;
            try {
                wanted = Exam.ExamStatus.valueOf(status.trim().toUpperCase());
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException(
                        "Unknown status \"" + status + "\". Use DRAFT, PUBLISHED, COMPLETED or CANCELLED.");
            }
            // A new mutable list: the sort below must not hit an immutable toList().
            exams = new ArrayList<>(exams.stream().filter(e -> e.getStatus() == wanted).toList());
        }
        exams.sort(Comparator
                .comparing((Exam e) -> e.getExamDate(), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Exam::getId));
        exams.forEach(this::attachSemesters);
        return exams;
    }

    @GetMapping("/{id}")
    public Exam getExam(@PathVariable Long id) {
        Exam exam = examRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Exam not found with id " + id));
        attachSemesters(exam);
        return exam;
    }

    @PostMapping
    public Exam addExam(@RequestBody Exam exam) {
        validate(exam);
        exam.setId(null);
        if (exam.getStatus() == null || exam.getStatus() == Exam.ExamStatus.PUBLISHED) {
            // New exams always start as drafts; they are published explicitly.
            exam.setStatus(Exam.ExamStatus.DRAFT);
        }
        Exam saved = examRepository.save(exam);
        if (exam.getSemesters() != null) {
            // Explicit Semester 1..8 selection from the publish form.
            examSemesterService.replaceSemesters(saved, exam.getSemesters());
            saved = examRepository.save(saved);
        }
        auditService.record(actor(), "EXAM_CREATED", "exam:" + saved.getId(), label(saved));
        attachSemesters(saved);
        return saved;
    }

    @PutMapping("/{id}")
    public Exam updateExam(@PathVariable Long id, @RequestBody Exam exam) {
        Exam existing = examRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Exam not found with id " + id));
        validate(exam);
        // Captured before the copy below so we can tell whether the schedule changed.
        LocalDate previousDate = existing.getExamDate();
        LocalTime previousTime = existing.getExamTime();
        LocalTime previousEndTime = existing.getEndTime();
        existing.setSubjectName(exam.getSubjectName());
        existing.setSubjectCode(exam.getSubjectCode());
        existing.setBranch(exam.getBranch());
        existing.setCourse(exam.getCourse());
        existing.setExamDate(exam.getExamDate());
        existing.setExamTime(exam.getExamTime());
        existing.setEndTime(exam.getEndTime());
        existing.setSemester(exam.getSemester());
        existing.setExamType(exam.getExamType());
        existing.setInstructions(exam.getInstructions());
        if (exam.getDurationMinutes() != null) existing.setDurationMinutes(exam.getDurationMinutes());
        existing.setApplicationId(exam.getApplicationId());
        // A published exam whose schedule is edited returns to draft so nobody sees
        // half-updated information: the administrator re-publishes explicitly.
        boolean scheduleChanged = !sameValue(previousDate, existing.getExamDate())
                || !sameValue(previousTime, existing.getExamTime())
                || !sameValue(previousEndTime, existing.getEndTime());
        Exam.ExamStatus previousStatus = existing.getStatus();
        if (existing.getStatus() == Exam.ExamStatus.PUBLISHED && scheduleChanged) {
            existing.setStatus(Exam.ExamStatus.DRAFT);
        }
        Exam saved = examRepository.save(existing);
        if (scheduleChanged && previousStatus == Exam.ExamStatus.PUBLISHED) {
            // Students had already seen the old schedule - tell them it moved.
            // (The exam itself returns to draft above; re-publishing stays explicit.)
            examNotificationService.examRescheduled(saved);
        }
        if (exam.getSemesters() != null) {
            // A provided selection (possibly empty) replaces the semester rows;
            // a request without the field keeps the current assignment untouched.
            examSemesterService.replaceSemesters(saved, exam.getSemesters());
            saved = examRepository.save(saved);
        }
        auditService.record(actor(), "EXAM_UPDATED", "exam:" + id, label(saved));
        attachSemesters(saved);
        return saved;
    }

    /** Lifecycle: DRAFT -> PUBLISHED -> COMPLETED, with CANCELLED reachable from any state. */
    @PostMapping("/{id}/status")
    public Exam setExamStatus(@PathVariable Long id, @RequestBody Map<String, String> body) {
        Exam exam = examRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Exam not found with id " + id));
        String wanted = body == null || body.get("status") == null ? "" : body.get("status").trim().toUpperCase();
        Exam.ExamStatus next;
        try {
            next = Exam.ExamStatus.valueOf(wanted);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown status \"" + wanted + "\". Use DRAFT, PUBLISHED, COMPLETED or CANCELLED.");
        }
        if (next == Exam.ExamStatus.PUBLISHED
                && (exam.getExamDate() == null || exam.getExamTime() == null)) {
            throw new IllegalArgumentException("Set an exam date and start time before publishing.");
        }
        exam.setStatus(next);
        examRepository.save(exam);
        if (next == Exam.ExamStatus.PUBLISHED) {
            // Targeted fan-out: only students eligible for this exam are notified.
            examNotificationService.examPublished(exam);
        }
        if (next == Exam.ExamStatus.CANCELLED) {
            // Cancelling hides the seating from students as well.
            List<SeatAllocation> allocations =
                    seatAllocationRepository.findByExamIdAndStatus(id, SeatAllocation.Status.PUBLISHED);
            allocations.forEach(a -> a.setStatus(SeatAllocation.Status.DRAFT));
            seatAllocationRepository.saveAll(allocations);
            examNotificationService.examCancelled(exam);
        }
        auditService.record(actor(), "EXAM_" + next, "exam:" + id, label(exam));
        return exam;
    }

    /** Replaces the batches this exam is published to (empty list = "all students"). */
    @PutMapping("/{id}/eligibility")
    public List<String> setEligibility(@PathVariable Long id, @RequestBody Map<String, List<String>> body) {
        Exam exam = examRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Exam not found with id " + id));
        List<String> batches = body == null || body.get("batches") == null ? List.of() : body.get("batches");
        eligibilityRepository.deleteByExamId(id);
        List<String> saved = new ArrayList<>();
        for (String batch : batches) {
            if (batch == null || batch.isBlank()) continue;
            String name = batch.trim();
            if (saved.contains(name)) continue;
            eligibilityRepository.save(new ExamEligibility(exam, name));
            saved.add(name);
        }
        auditService.record(actor(), "EXAM_ELIGIBILITY", "exam:" + id, "Batches: " + saved);
        return saved;
    }

    @GetMapping("/{id}/eligibility")
    public List<String> getEligibility(@PathVariable Long id) {
        return eligibilityRepository.findByExamId(id).stream().map(ExamEligibility::getBatchName).toList();
    }

    /** Semesters this exam is published to (empty list = every semester). */
    @GetMapping("/{id}/semesters")
    public List<Integer> getSemesters(@PathVariable Long id) {
        Exam exam = examRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Exam not found with id " + id));
        return examSemesterService.assignedSemesters(exam).stream().sorted().toList();
    }

    /** Replaces the semesters this exam is published to (empty list = every semester). */
    @PutMapping("/{id}/semesters")
    public List<Integer> setSemesters(@PathVariable Long id, @RequestBody Map<String, List<Integer>> body) {
        Exam exam = examRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Exam not found with id " + id));
        List<Integer> wanted = ExamSemesterService.validateSemesters(body == null ? null : body.get("semesters"));
        examSemesterService.replaceSemesters(exam, wanted);
        Exam saved = examRepository.save(exam);
        auditService.record(actor(), "EXAM_SEMESTERS", "exam:" + id,
                "Semesters: " + (wanted.isEmpty() ? "all" : wanted));
        return examSemesterService.assignedSemesters(saved).stream().sorted().toList();
    }

    /** Only draft or cancelled exams can be deleted (published ones must be cancelled first). */
    @DeleteMapping("/{id}")
    public Map<String, Object> deleteExam(@PathVariable Long id) {
        Exam exam = examRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Exam not found with id " + id));
        if (exam.getStatus() == Exam.ExamStatus.PUBLISHED) {
            throw new IllegalArgumentException(
                    "Cancel the exam before deleting it - it is currently published to students.");
        }
        seatAllocationRepository.deleteByExamId(id);
        eligibilityRepository.deleteByExamId(id);
        examSemesterService.clear(id);
        // Rooms remembered for a generated chart (exam_rooms) hold an FK to the
        // exam - they must go too, otherwise the delete fails with a data conflict.
        examRoomRepository.deleteByExamId(id);
        seatRepository.deleteAll(seatRepository.findByExamId(id));
        examRepository.deleteById(id);
        auditService.record(actor(), "EXAM_DELETED", "exam:" + id, label(exam));
        return Map.of("deleted", true);
    }

    // ------------------------------------------------------------------ helpers

    /** Keeps obviously broken exam rows out of the database. */
    private void validate(Exam exam) {
        if (exam.getSubjectName() == null || exam.getSubjectName().isBlank()) {
            throw new IllegalArgumentException("Subject name is required.");
        }
        exam.setSubjectName(exam.getSubjectName().trim());
        if (exam.getExamTime() != null && exam.getEndTime() != null
                && !exam.getEndTime().isAfter(exam.getExamTime())) {
            throw new IllegalArgumentException("The end time must be after the start time.");
        }
        ExamSemesterService.validateSemester(exam.getSemester());
        if (exam.getSemesters() != null) {
            ExamSemesterService.validateSemesters(exam.getSemesters());
        }
    }

    /** Copies the exam's semester audience (rows, else legacy column) into the JSON. */
    private void attachSemesters(Exam exam) {
        exam.setSemesters(new LinkedHashSet<>(examSemesterService.assignedSemesters(exam)));
    }

    private boolean sameValue(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }

    private String label(Exam exam) {
        return (exam.getSubjectName() == null || exam.getSubjectName().isBlank()
                ? "exam" : exam.getSubjectName())
                + (exam.getExamDate() == null ? "" : " on " + exam.getExamDate());
    }

    private String actor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? "system" : auth.getName();
    }
}
