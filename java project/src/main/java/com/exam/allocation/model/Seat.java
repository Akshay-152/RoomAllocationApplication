package com.exam.allocation.model;

import jakarta.persistence.*;

@Entity
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // "row_number" is a reserved word on MySQL 8: quote it, otherwise Hibernate's
    // generated CREATE TABLE fails and the whole seat table is never created.
    @Column(name = "`row_number`", nullable = false)
    private int rowNumber;

    @Column(nullable = false)
    private int columnNumber;

    /** Which student position at the desk (1..studentsPerDesk); desks can seat several students. */
    @Column(nullable = false)
    private int deskPosition = 1;

    @ManyToOne
    @JoinColumn(name = "room_id")
    private Room room;

    // Many-to-One: a student can appear in the seating chart of several exams.
    // (Was @OneToOne, which created a UNIQUE constraint on the column and made
    //  re-running the allocation fail with a DataIntegrityViolationException.)
    @ManyToOne
    @JoinColumn(name = "allocated_student_id")
    private Student allocatedStudent;
    
    @ManyToOne
    @JoinColumn(name = "exam_id")
    private Exam exam;

    public Seat() {}

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public int getRowNumber() { return rowNumber; }
    public void setRowNumber(int rowNumber) { this.rowNumber = rowNumber; }

    public int getColumnNumber() { return columnNumber; }
    public void setColumnNumber(int columnNumber) { this.columnNumber = columnNumber; }

    public int getDeskPosition() { return deskPosition; }
    public void setDeskPosition(int deskPosition) { this.deskPosition = deskPosition; }

    public Room getRoom() { return room; }
    public void setRoom(Room room) { this.room = room; }

    public Student getAllocatedStudent() { return allocatedStudent; }
    public void setAllocatedStudent(Student allocatedStudent) { this.allocatedStudent = allocatedStudent; }

    public Exam getExam() { return exam; }
    public void setExam(Exam exam) { this.exam = exam; }
}
