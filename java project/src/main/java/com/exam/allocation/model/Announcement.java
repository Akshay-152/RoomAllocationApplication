package com.exam.allocation.model;

import jakarta.persistence.*;
import java.time.Instant;

/** An examination announcement ({@code announcements} table) shown on student dashboards. */
@Entity
@Table(name = "announcements")
public class Announcement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(length = 2000)
    private String body;

    /** Batch the announcement targets; null/blank = every student. */
    private String batchName;

    /** Semester the announcement targets; null = every semester. */
    private Integer semester;

    /** Comma-separated roll numbers for individual targeting; null/blank = not used. */
    @Column(length = 2000)
    private String rollNos;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    public Announcement() {}

    public Announcement(String title, String body, String batchName) {
        this.title = title;
        this.body = body;
        this.batchName = batchName;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }

    public String getBatchName() { return batchName; }
    public void setBatchName(String batchName) { this.batchName = batchName; }

    public Integer getSemester() { return semester; }
    public void setSemester(Integer semester) { this.semester = semester; }

    public String getRollNos() { return rollNos; }
    public void setRollNos(String rollNos) { this.rollNos = rollNos; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
