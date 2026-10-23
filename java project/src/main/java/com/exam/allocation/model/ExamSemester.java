package com.exam.allocation.model;

import jakarta.persistence.*;

/**
 * Which semester(s) an exam is published to ({@code exam_semesters} table).
 *
 * <p>An exam can target one or more semesters (Semester 1..8). When an exam has
 * no semester rows, its legacy {@code exam.semester} column decides the audience
 * (null = every semester), so imported/existing exams keep working unchanged.
 */
@Entity
@Table(name = "exam_semesters",
        uniqueConstraints = @UniqueConstraint(columnNames = {"exam_id", "semester_number"}))
public class ExamSemester {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "exam_id")
    private Exam exam;

    /** Semester number this row assigns, always between 1 and 8. */
    @Column(name = "semester_number", nullable = false)
    private Integer semesterNumber;

    public ExamSemester() {}

    public ExamSemester(Exam exam, Integer semesterNumber) {
        this.exam = exam;
        this.semesterNumber = semesterNumber;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Exam getExam() { return exam; }
    public void setExam(Exam exam) { this.exam = exam; }

    public Integer getSemesterNumber() { return semesterNumber; }
    public void setSemesterNumber(Integer semesterNumber) { this.semesterNumber = semesterNumber; }
}
