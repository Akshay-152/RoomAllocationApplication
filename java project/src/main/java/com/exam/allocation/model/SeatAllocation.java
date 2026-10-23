package com.exam.allocation.model;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * One student's seat in one exam ({@code seat_allocations} table).
 *
 * <p>Written by the allocation service and only shown to students once it is
 * {@code published}. Uniqueness constraints enforce the core rules:
 * a student gets one seat per exam, and no two students share a seat.
 */
@Entity
@Table(name = "seat_allocations",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"exam_id", "student_id"}),
                @UniqueConstraint(columnNames = {"exam_id", "seat_id"})
        })
public class SeatAllocation {

    public enum Status { DRAFT, PUBLISHED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "exam_id")
    private Exam exam;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id")
    private Student student;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id")
    private Room room;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seat_id")
    private Seat seat;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.DRAFT;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    public SeatAllocation() {}

    public SeatAllocation(Exam exam, Student student, Room room, Seat seat) {
        this.exam = exam;
        this.student = student;
        this.room = room;
        this.seat = seat;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Exam getExam() { return exam; }
    public void setExam(Exam exam) { this.exam = exam; }

    public Student getStudent() { return student; }
    public void setStudent(Student student) { this.student = student; }

    public Room getRoom() { return room; }
    public void setRoom(Room room) { this.room = room; }

    public Seat getSeat() { return seat; }
    public void setSeat(Seat seat) { this.seat = seat; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
