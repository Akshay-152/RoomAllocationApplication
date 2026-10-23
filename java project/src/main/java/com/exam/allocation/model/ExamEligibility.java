package com.exam.allocation.model;

import jakarta.persistence.*;

/**
 * Which batch(es) an exam is published to ({@code exam_eligibility} table).
 *
 * <p>When an exam has no eligibility rows, its legacy {@code branch}/{@code course}
 * columns decide the audience (blank = every student), so imported exams keep
 * working unchanged.
 */
@Entity
@Table(name = "exam_eligibility",
        uniqueConstraints = @UniqueConstraint(columnNames = {"exam_id", "batch_name"}))
public class ExamEligibility {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "exam_id")
    private Exam exam;

    @Column(name = "batch_name", nullable = false)
    private String batchName;

    public ExamEligibility() {}

    public ExamEligibility(Exam exam, String batchName) {
        this.exam = exam;
        this.batchName = batchName;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Exam getExam() { return exam; }
    public void setExam(Exam exam) { this.exam = exam; }

    public String getBatchName() { return batchName; }
    public void setBatchName(String batchName) { this.batchName = batchName; }
}
