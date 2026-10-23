package com.exam.allocation.model;

import jakarta.persistence.*;

/**
 * A course/batch option available to students and exams
 * (default options: A, B, C, D, E, F, G - custom ones can be added
 * through the "Add Course" button in the UI).
 *
 * <p>Rows marked {@code custom = false} are the built-in defaults and are
 * re-seeded automatically on startup if the table is empty.
 */
@Entity
@Table(name = "batch")
public class Batch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String name;

    /** false = built-in default (A..G), true = user created via "Add Course". */
    @Column(nullable = false)
    private boolean custom = false;

    public Batch() {}

    public Batch(String name, boolean custom) {
        this.name = name;
        this.custom = custom;
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public boolean isCustom() { return custom; }
    public void setCustom(boolean custom) { this.custom = custom; }
}
