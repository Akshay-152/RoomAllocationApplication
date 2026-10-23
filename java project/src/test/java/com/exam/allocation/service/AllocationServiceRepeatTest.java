package com.exam.allocation.service;

import com.exam.allocation.model.Exam;
import com.exam.allocation.model.Room;
import com.exam.allocation.model.Seat;
import com.exam.allocation.model.SeatAllocation;
import com.exam.allocation.model.Student;
import com.exam.allocation.repository.ExamEligibilityRepository;
import com.exam.allocation.repository.ExamRepository;
import com.exam.allocation.repository.RoomRepository;
import com.exam.allocation.repository.SeatAllocationRepository;
import com.exam.allocation.repository.SeatRepository;
import com.exam.allocation.repository.StudentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;

/**
 * The seat-fairness rule end to end through {@code generateAllocation}:
 * the stored {@code seat_allocations} history decides where a student may
 * sit - at most two seatings per room - and a repeat is only allowed when the
 * chart has no free alternative seat (attendance first, counted as a violation).
 */
@ExtendWith(MockitoExtension.class)
class AllocationServiceRepeatTest {

    @Mock private StudentRepository studentRepository;
    @Mock private RoomRepository roomRepository;
    @Mock private SeatRepository seatRepository;
    @Mock private ExamRepository examRepository;
    @Mock private ConflictService conflictService;
    @Mock private SeatAllocationRepository seatAllocationRepository;
    @Mock private ExamEligibilityRepository examEligibilityRepository;
    @Mock private ExamSemesterService examSemesterService;

    @InjectMocks private AllocationService allocationService;

    private final Map<Long, List<Seat>> seatsByRoom = new HashMap<>();
    private final List<SeatAllocation> history = new ArrayList<>();
    private Exam exam;

    @BeforeEach
    void wireMocks() {
        exam = new Exam();
        exam.setId(1L);
        exam.setSubjectName("Fairness Exam");

        lenient().when(examRepository.findById(1L)).thenReturn(Optional.of(exam));
        lenient().when(examSemesterService.matchesStudent(any(), any())).thenReturn(true);
        lenient().when(examEligibilityRepository.findByExamId(anyLong())).thenReturn(List.of());
        lenient().when(conflictService.allocationWarnings(any(), any())).thenReturn(new ArrayList<>());
        lenient().when(seatAllocationRepository.findAll()).thenAnswer(inv -> new ArrayList<>(history));
        lenient().when(seatRepository.findByRoomId(anyLong()))
                .thenAnswer(inv -> seatsByRoom.computeIfAbsent(inv.getArgument(0), id -> new ArrayList<>()));
        lenient().when(seatRepository.saveAll(anyList())).thenAnswer(inv -> {
            List<Seat> saved = inv.getArgument(0);
            for (Seat seat : saved) {
                List<Seat> bucket = seatsByRoom.computeIfAbsent(seat.getRoom().getId(), id -> new ArrayList<>());
                if (!bucket.contains(seat)) bucket.add(seat);
            }
            return saved;
        });
    }

    // ------------------------------------------------------------------ tests

    @Test
    void withoutHistoryEveryStudentIsSeatedAsBefore() {
        stubRooms(room(10L));
        stubStudents(4);

        AllocationService.AllocationResult result = allocationService.generateAllocation(1L, List.of(10L));

        assertTrue(result.isSuccess());
        assertEquals(4, result.getAllocated());
        assertEquals(0, result.getUnallocated());
        assertEquals(1, result.getRoomsUsed());
        assertEquals(0, result.getRepeatViolations());
        assertEquals(0, result.getHistorySeatings());
        assertEquals(4, seatedIn(10L));
        assertTrue(result.getMessage().contains("Seat-fairness"));
    }

    @Test
    void fillsEachBenchAndUsesTheLargestSelectedRoomFirst() {
        Room small = room(10L);
        Room large = room(20L);
        large.setStudentsPerDesk(2);
        stubRooms(small, large);
        stubStudents(5);

        AllocationService.AllocationResult result =
                allocationService.generateAllocation(1L, List.of(10L, 20L));

        assertEquals(5, result.getAllocated());
        assertEquals(3, result.getBenchesUsed());
        assertEquals(1, result.getRoomsUsed());
        assertEquals(0, seatedIn(10L));
        assertEquals(5, seatedIn(20L));
        List<Seat> occupied = seatsByRoom.get(20L).stream()
                .filter(seat -> seat.getAllocatedStudent() != null)
                .toList();
        assertEquals(2, occupied.stream()
                .filter(seat -> seat.getRowNumber() == 1 && seat.getColumnNumber() == 1)
                .count());
        assertEquals(2, occupied.stream()
                .filter(seat -> seat.getRowNumber() == 1 && seat.getColumnNumber() == 2)
                .count());
        assertEquals(1, occupied.stream()
                .filter(seat -> seat.getRowNumber() == 2 && seat.getColumnNumber() == 1)
                .count());
    }

    @Test
    void studentsAtTheRoomCapAreMovedToTheSecondRoom() {
        Room roomA = room(10L);
        Room roomB = room(20L);
        stubRooms(roomA, roomB);
        List<Student> students = stubStudents(4);
        // Everyone already sat twice in room A (two different desks each).
        for (Student student : students) {
            record(student, roomA, 1, 1, 1);
            record(student, roomA, 2, 2, 1);
        }

        AllocationService.AllocationResult result = allocationService.generateAllocation(1L, List.of(10L, 20L));

        assertTrue(result.isSuccess());
        assertEquals(4, result.getAllocated());
        assertEquals(0, result.getRepeatViolations());
        assertEquals(8, result.getHistorySeatings());
        assertEquals(0, seatedIn(10L));    // the capped room stays empty
        assertEquals(4, seatedIn(20L));    // everyone got the fresh room
        assertEquals(1, result.getRoomsUsed());
    }

    @Test
    void aRepeatIsOnlyAllowedWhenNoAlternativeSeatExists() {
        stubRooms(room(10L));
        List<Student> students = stubStudents(2);
        record(students.get(0), room(10L), 1, 1, 1);
        record(students.get(0), room(10L), 2, 2, 1);

        AllocationService.AllocationResult result = allocationService.generateAllocation(1L, List.of(10L));

        assertTrue(result.isSuccess());
        assertEquals(2, result.getAllocated());        // attendance first: both seated
        assertEquals(1, result.getRepeatViolations()); // only the capped student repeats
        assertEquals(2, seatedIn(10L));
        assertFalse(result.getConflicts().isEmpty());
        assertTrue(result.getConflicts().get(result.getConflicts().size() - 1).contains("2-times"),
                result.getConflicts().toString());
    }

    @Test
    void aCappedStudentFillsTheLeftoverDeskPositionAfterTheRelaxation() {
        Room roomA = room(10L);
        roomA.setStudentsPerDesk(2);   // one desk with two positions
        stubRooms(roomA);
        List<Student> students = stubStudents(2);
        record(students.get(0), roomA, 1, 1, 1);
        record(students.get(0), roomA, 2, 2, 1);

        AllocationService.AllocationResult result = allocationService.generateAllocation(1L, List.of(10L));

        List<Seat> seats = seatsByRoom.get(10L);
        Seat pos1 = seats.stream().filter(s -> s.getDeskPosition() == 1).findFirst().orElseThrow();
        Seat pos2 = seats.stream().filter(s -> s.getDeskPosition() == 2).findFirst().orElseThrow();
        assertSame(students.get(1), pos1.getAllocatedStudent());   // uncapped student sits first
        assertSame(students.get(0), pos2.getAllocatedStudent());   // capped student gets the leftover
        assertEquals(2, result.getAllocated());
        assertEquals(1, result.getRepeatViolations());
    }

    @Test
    void partialHistoryLeavesUncappedStudentsInTheCappedRoom() {
        Room roomA = room(10L);
        stubRooms(roomA);
        List<Student> students = stubStudents(2);
        record(students.get(0), roomA, 1, 1, 1);
        record(students.get(0), roomA, 2, 2, 1);
        record(students.get(1), roomA, 1, 2, 1);   // only once - still allowed

        AllocationService.AllocationResult result = allocationService.generateAllocation(1L, List.of(10L));

        List<Seat> seats = seatsByRoom.get(10L);
        assertTrue(seats.stream().anyMatch(s -> s.getAllocatedStudent() == students.get(1)));
        assertTrue(seats.stream().anyMatch(s -> s.getAllocatedStudent() == students.get(0)));
        assertEquals(2, result.getAllocated());
        assertEquals(1, result.getRepeatViolations());
    }

    // ------------------------------------------------------------------ fixtures

    /** Creates rooms with 4 seats each (2x2, one student per desk) and stubs the repository. */
    private void stubRooms(Room... rooms) {
        List<Room> all = List.of(rooms);
        for (Room room : rooms) {
            if (room.getCapacity() == 0) {
                room.setCapacity(room.getRowsCount() * room.getColumnsCount() * room.getStudentsPerDesk());
            }
            room.setActive(true);
        }
        lenient().when(roomRepository.findAllById(any())).thenReturn(new ArrayList<>(all));
    }

    private Room room(Long id) {
        Room room = new Room();
        room.setId(id);
        room.setRoomNumber("R" + id);
        room.setRowsCount(2);
        room.setColumnsCount(2);
        room.setStudentsPerDesk(1);
        return room;
    }

    private List<Student> stubStudents(int count) {
        List<Student> students = new ArrayList<>();
        for (long i = 1; i <= count; i++) {
            Student student = new Student();
            student.setId(i);
            student.setRollNo("9" + i);
            student.setBranch("CEC");
            student.setBatch("E");
            student.setSemester(2);
            student.setAccountStatus(Student.AccountStatus.ACTIVE);
            students.add(student);
        }
        lenient().when(studentRepository.findAll()).thenReturn(new ArrayList<>(students));
        return students;
    }

    private void record(Student student, Room room, int row, int col, int position) {
        Seat seat = new Seat();
        seat.setRoom(room);
        seat.setRowNumber(row);
        seat.setColumnNumber(col);
        seat.setDeskPosition(position);
        history.add(new SeatAllocation(exam, student, room, seat));
    }

    private long seatedIn(Long roomId) {
        return seatsByRoom.getOrDefault(roomId, List.of()).stream()
                .filter(s -> s.getAllocatedStudent() != null)
                .count();
    }
}
