package com.exam.allocation.service;

import com.exam.allocation.model.Exam;
import com.exam.allocation.model.Room;
import com.exam.allocation.model.Seat;
import com.exam.allocation.model.SeatAllocation;
import com.exam.allocation.model.Student;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The seat-fairness ledger: how the stored seating history maps onto the
 * 2-times room / desk-position caps that generateAllocation enforces.
 */
class SeatRepeatGuardTest {

    @Test
    void anEmptyHistoryNeverBlocks() {
        SeatRepeatGuard guard = SeatRepeatGuard.analyze(List.of());

        assertFalse(guard.roomAtCap(student(1L), room(10L)));
        assertFalse(guard.seatAtCap(student(1L), seat(room(10L), 1, 1, 1)));
        assertEquals(0, guard.getHistorySeatings());
        assertEquals(0, guard.getViolations());
    }

    @Test
    void aStudentWithTwoSeatingsInTheSameRoomIsAtTheCap() {
        Room roomA = room(10L);
        SeatRepeatGuard guard = SeatRepeatGuard.analyze(List.of(
                allocation(student(1L), roomA, seat(roomA, 1, 1, 1)),
                allocation(student(1L), roomA, seat(roomA, 2, 2, 1))));

        assertEquals(2, guard.getHistorySeatings());
        assertTrue(guard.roomAtCap(student(1L), roomA));
        assertTrue(guard.roomAtCap(student(1L), room(10L)));   // same room id
        assertFalse(guard.roomAtCap(student(1L), room(20L)));  // another room is still free
        assertFalse(guard.roomAtCap(student(2L), roomA));      // another student is unaffected
    }

    @Test
    void aDeskPositionRepeatIsTrackedPerRoomAndPosition() {
        Room roomA = room(10L);
        Seat pos1 = seat(roomA, 1, 1, 1);
        SeatRepeatGuard guard = SeatRepeatGuard.analyze(List.of(
                allocation(student(1L), roomA, pos1),
                allocation(student(1L), roomA, seat(roomA, 1, 1, 1))));

        assertTrue(guard.seatAtCap(student(1L), pos1));
        assertFalse(guard.seatAtCap(student(1L), seat(roomA, 1, 1, 2)));  // other position
        assertFalse(guard.seatAtCap(student(1L), seat(roomA, 1, 2, 1)));  // other desk
    }

    @Test
    void placementsOverTheCapAreCountedAsViolations() {
        Room roomA = room(10L);
        SeatRepeatGuard guard = SeatRepeatGuard.analyze(List.of(
                allocation(student(1L), roomA, seat(roomA, 1, 1, 1)),
                allocation(student(1L), roomA, seat(roomA, 2, 2, 1))));

        guard.record(student(1L), seat(roomA, 3, 3, 1));   // third time in room A
        guard.record(student(2L), seat(roomA, 1, 1, 1));   // first time for a fresh student

        assertEquals(1, guard.getViolations());
    }

    @Test
    void historyRowsWithoutStudentAreIgnored() {
        SeatRepeatGuard guard = SeatRepeatGuard.analyze(
                java.util.Arrays.asList(new SeatAllocation(), null));

        assertEquals(0, guard.getHistorySeatings());
        assertEquals(0, guard.getViolations());
    }

    // ------------------------------------------------------------------ fixtures

    private static Student student(Long id) {
        Student student = new Student();
        student.setId(id);
        return student;
    }

    private static Room room(Long id) {
        Room room = new Room();
        room.setId(id);
        room.setRoomNumber("R" + id);
        return room;
    }

    private static Seat seat(Room room, int row, int col, int position) {
        Seat seat = new Seat();
        seat.setRoom(room);
        seat.setRowNumber(row);
        seat.setColumnNumber(col);
        seat.setDeskPosition(position);
        return seat;
    }

    private static SeatAllocation allocation(Student student, Room room, Seat seat) {
        Exam exam = new Exam();
        exam.setId(99L);
        return new SeatAllocation(exam, student, room, seat);
    }
}
