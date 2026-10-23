package com.exam.allocation.model;

import jakarta.persistence.*;

/** A room booked for an exam ({@code exam_rooms} table). */
@Entity
@Table(name = "exam_rooms",
        uniqueConstraints = @UniqueConstraint(columnNames = {"exam_id", "room_id"}))
public class ExamRoom {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "exam_id")
    private Exam exam;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id")
    private Room room;

    public ExamRoom() {}

    public ExamRoom(Exam exam, Room room) {
        this.exam = exam;
        this.room = room;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Exam getExam() { return exam; }
    public void setExam(Exam exam) { this.exam = exam; }

    public Room getRoom() { return room; }
    public void setRoom(Room room) { this.room = room; }
}
