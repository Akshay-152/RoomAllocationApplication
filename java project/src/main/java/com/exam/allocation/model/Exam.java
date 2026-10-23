package com.exam.allocation.model;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * An exam event that students can be seated for.
 *
 * <p>Audience targeting (both optional - blank means "all students"):
 * <ul>
 *   <li>{@code branch}  - only students of this branch, e.g. "CEC"</li>
 *   <li>{@code course}  - the batch/course the exam is for, e.g. "E"
 *       (also matches a branch of the same name for imported/legacy data)</li>
 * </ul>
 */
@Entity
public class Exam {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String subjectName;

    /** Branch this exam is for; null/blank = all branches. */
    private String branch;

    /** Batch/Course this exam is for, e.g. "E"; null/blank = all batches. */
    private String course;

    private LocalDate examDate;

    private LocalTime examTime;

    /** Application identifier of the exam, e.g. "APP-E-2026-001". */
    private String applicationId;

    /** Subject catalogue code, e.g. "CS301". */
    private String subjectCode;

    /** End time of the exam; used to compute the duration. */
    private LocalTime endTime;

    /** Semester the exam is for (1..8). Legacy single-semester audience. */
    private Integer semester;

    /**
     * Multi-semester audience (Semester 1..8) used by the publish form.
     * Persisted as {@code exam_semesters} rows (see {@link ExamSemester});
     * null/empty means the legacy {@link #semester} column decides (null = every
     * semester). Not a database column - populated on read and written through
     * the exam endpoints.
     */
    @Transient
    private java.util.Set<Integer> semesters;

    /** Free-form exam type, e.g. "Regular", "Supplementary", "Internal". */
    private String examType;

    /** Instructions shown to students on their dashboard and seating slip. */
    @Column(length = 2000)
    private String instructions;

    /** Lifecycle state: DRAFT, PUBLISHED, COMPLETED or CANCELLED. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExamStatus status = ExamStatus.DRAFT;

    public enum ExamStatus { DRAFT, PUBLISHED, COMPLETED, CANCELLED }

    /** Duration in whole minutes, derived from start/end times when both exist. */
    @Column(name = "duration_minutes")
    private Integer durationMinutes;

    public Exam() {}

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSubjectName() { return subjectName; }
    public void setSubjectName(String subjectName) { this.subjectName = subjectName; }

    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }

    public String getCourse() { return course; }
    public void setCourse(String course) { this.course = course; }

    public LocalDate getExamDate() { return examDate; }
    public void setExamDate(LocalDate examDate) { this.examDate = examDate; }

    public LocalTime getExamTime() { return examTime; }
    public void setExamTime(LocalTime examTime) { this.examTime = examTime; }

    public String getApplicationId() { return applicationId; }
    public void setApplicationId(String applicationId) { this.applicationId = applicationId; }

    public String getSubjectCode() { return subjectCode; }
    public void setSubjectCode(String subjectCode) { this.subjectCode = subjectCode; }

    public LocalTime getEndTime() { return endTime; }
    public void setEndTime(LocalTime endTime) { this.endTime = endTime; this.durationMinutes = deriveDuration(); }

    public Integer getSemester() { return semester; }
    public void setSemester(Integer semester) { this.semester = semester; }

    public java.util.Set<Integer> getSemesters() { return semesters; }
    public void setSemesters(java.util.Set<Integer> semesters) { this.semesters = semesters; }

    public String getExamType() { return examType; }
    public void setExamType(String examType) { this.examType = examType; }

    public String getInstructions() { return instructions; }
    public void setInstructions(String instructions) { this.instructions = instructions; }

    public ExamStatus getStatus() { return status; }
    public void setStatus(ExamStatus status) { this.status = status; }

    public Integer getDurationMinutes() { return durationMinutes != null ? durationMinutes : deriveDuration(); }
    public void setDurationMinutes(Integer durationMinutes) { this.durationMinutes = durationMinutes; }

    /**
     * Recomputed whenever the start or end time changes; null when unknown.
     *
     * <p>Returns early instead of using a ternary: mixing {@code int} and
     * {@code Integer} in one conditional expression would unbox the (possibly
     * null) stored value, so a request whose end time is before its start time
     * used to fail with a NullPointerException instead of being validated.
     */
    private Integer deriveDuration() {
        if (examTime == null || endTime == null) return durationMinutes;
        int minutes = (int) java.time.Duration.between(examTime, endTime).toMinutes();
        if (minutes > 0) return minutes;
        return durationMinutes;
    }
}
