package com.exam.allocation.service;

import com.exam.allocation.model.Exam;
import com.exam.allocation.model.Notification;
import com.exam.allocation.model.Notification.Type;
import com.exam.allocation.model.Room;
import com.exam.allocation.model.Seat;
import com.exam.allocation.model.SeatAllocation;
import com.exam.allocation.model.Student;
import com.exam.allocation.repository.NotificationRepository;
import com.exam.allocation.repository.StudentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Targeting rules for event-driven notifications:
 * - only students matching the exam's persisted audience are notified,
 * - a re-published identical seat is deduplicated, a moved seat produces a
 *   "seat changed" notification,
 * - room edits only reach students seated in that room for a published exam.
 */
@ExtendWith(MockitoExtension.class)
class ExamNotificationServiceTest {

    @Mock private StudentRepository studentRepository;
    @Mock private AllocationService allocationService;
    @Mock private NotificationService notificationService;
    @Mock private NotificationRepository notificationRepository;
    @Mock private NotificationTemplateService notificationTemplateService;

    @InjectMocks private ExamNotificationService examNotificationService;

    /** Every event renders through the template service; tests assert on
     *  targeting, dedupe and types - not on the wording itself. */
    @BeforeEach
    void renderDefaultWordings() {
        lenient().when(notificationTemplateService.render(any(Type.class), anyMap()))
                .thenReturn(new NotificationTemplateService.Message("Rendered title", "Rendered body"));
    }

    private Student student(long id, String roll, String batch) {
        Student s = new Student();
        s.setId(id);
        s.setRollNo(roll);
        s.setBatch(batch);
        s.setAccountStatus(Student.AccountStatus.ACTIVE);
        return s;
    }

    private Exam exam(long id, String subject) {
        Exam e = new Exam();
        e.setId(id);
        e.setSubjectName(subject);
        e.setExamDate(LocalDate.of(2026, 5, 4));
        e.setExamTime(LocalTime.of(10, 0));
        e.setStatus(Exam.ExamStatus.PUBLISHED);
        return e;
    }

    private SeatAllocation allocation(Exam exam, Student student, Room room, int row, int col) {
        Seat seat = new Seat();
        seat.setRowNumber(row);
        seat.setColumnNumber(col);
        SeatAllocation allocation = new SeatAllocation();
        allocation.setExam(exam);
        allocation.setStudent(student);
        allocation.setRoom(room);
        allocation.setSeat(seat);
        allocation.setStatus(SeatAllocation.Status.PUBLISHED);
        return allocation;
    }

    // ------------------------------------------------------------------ audience

    @Test
    void onlyActiveStudentsMatchingTheExamAreNotified() {
        Exam maths = exam(1L, "Maths");
        Student in = student(1L, "101", "E");
        Student out = student(2L, "102", "F");
        Student inactive = student(3L, "103", "E");
        inactive.setAccountStatus(Student.AccountStatus.INACTIVE);
        when(studentRepository.findAll()).thenReturn(List.of(in, out, inactive));
        when(allocationService.matchesExam(in, maths)).thenReturn(true);
        when(allocationService.matchesExam(out, maths)).thenReturn(false);
        // The inactive student never reaches matchesExam (filtered first).
        when(notificationService.notify(anyList(), any(Type.class), anyString(), anyString(),
                anyString(), anyLong(), anyString())).thenReturn(new NotificationService.FanOutResult());

        examNotificationService.examPublished(maths);

        ArgumentCaptor<List<Student>> targets = ArgumentCaptor.forClass(List.class);
        verify(notificationService).notify(targets.capture(), eq(Type.EXAM_PUBLISHED),
                anyString(), anyString(), eq("exams"), eq(1L), anyString());
        assertEquals(1, targets.getValue().size());
        assertEquals("101", targets.getValue().get(0).getRollNo());
    }

    @Test
    void publishAndCancelUseDistinctDeterministicDedupeKeys() {
        Exam maths = exam(1L, "Maths");
        when(studentRepository.findAll()).thenReturn(List.of(student(1L, "101", "E")));
        when(allocationService.matchesExam(any(), eq(maths))).thenReturn(true);
        when(notificationService.notify(anyList(), any(Type.class), anyString(), anyString(),
                anyString(), anyLong(), anyString())).thenReturn(new NotificationService.FanOutResult());

        examNotificationService.examPublished(maths);
        examNotificationService.examCancelled(maths);

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(notificationService, org.mockito.Mockito.times(2))
                .notify(anyList(), any(Type.class), anyString(), anyString(),
                        anyString(), eq(1L), keys.capture());
        // "published:2026-05-04T10:00" vs "cancelled:..." - a retry repeats the
        // same key (deduped) while a genuine cancel notifies again.
        assertTrue(keys.getAllValues().get(0).startsWith("exam:1:published:"));
        assertTrue(keys.getAllValues().get(1).startsWith("exam:1:cancelled:"));
    }

    // ------------------------------------------------------------------ seats

    @Test
    void aFreshPublishNotifiesEachSeatedStudentOnce() {
        Exam maths = exam(1L, "Maths");
        Room room = new Room();
        room.setId(10L);
        room.setRoomNumber("101");
        Student a = student(1L, "101", "E");
        Student b = student(2L, "102", "E");
        when(notificationRepository.findTopByStudentIdAndExamIdAndTypeInOrderByCreatedAtDesc(
                anyLong(), anyLong(), any())).thenReturn(Optional.empty());
        when(notificationService.notify(any(Student.class), any(Type.class), anyString(),
                anyString(), anyString(), anyLong(), anyString()))
                .thenReturn(new NotificationService.FanOutResult());

        examNotificationService.seatingPublished(maths, List.of(
                allocation(maths, a, room, 1, 1), allocation(maths, b, room, 1, 2)));

        verify(notificationService).notify(eq(a), eq(Type.SEAT_PUBLISHED), anyString(),
                anyString(), eq("seat"), eq(1L), anyString());
        verify(notificationService).notify(eq(b), eq(Type.SEAT_PUBLISHED), anyString(),
                anyString(), eq("seat"), eq(1L), anyString());
    }

    @Test
    void republishingTheSameSeatIsDeduplicated() {
        Exam maths = exam(1L, "Maths");
        Room room = new Room();
        room.setId(10L);
        room.setRoomNumber("101");
        Student a = student(1L, "101", "E");
        SeatAllocation seat = allocation(maths, a, room, 2, 3);
        // The student already holds a notification whose key encodes this seat.
        Notification previous = new Notification(1L, Type.SEAT_PUBLISHED, "T", null, "seat", 1L,
                "seat:1:Room 101, seat R2-C03");
        when(notificationRepository.findTopByStudentIdAndExamIdAndTypeInOrderByCreatedAtDesc(
                eq(1L), eq(1L), any())).thenReturn(Optional.of(previous));

        examNotificationService.seatingPublished(maths, List.of(seat));

        verify(notificationService, never()).notify(any(Student.class), any(Type.class),
                anyString(), anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    void aMovedSeatProducesASeatChangedNotification() {
        Exam maths = exam(1L, "Maths");
        Room room = new Room();
        room.setId(10L);
        room.setRoomNumber("101");
        Student a = student(1L, "101", "E");
        SeatAllocation moved = allocation(maths, a, room, 5, 6);
        Notification previous = new Notification(1L, Type.SEAT_PUBLISHED, "T", null, "seat", 1L,
                "seat:1:Room 101, seat R2-C03");
        when(notificationRepository.findTopByStudentIdAndExamIdAndTypeInOrderByCreatedAtDesc(
                eq(1L), eq(1L), any())).thenReturn(Optional.of(previous));
        when(notificationService.notify(any(Student.class), any(Type.class), anyString(),
                anyString(), anyString(), anyLong(), anyString()))
                .thenReturn(new NotificationService.FanOutResult());

        examNotificationService.seatingPublished(maths, List.of(moved));

        verify(notificationService).notify(eq(a), eq(Type.SEAT_CHANGED), anyString(), anyString(),
                eq("seat"), eq(1L), anyString());
    }

    // ------------------------------------------------------------------ rooms

    @Test
    void roomChangesOnlyReachStudentsOfPublishedExamsInThatRoom() {
        Room room = new Room();
        room.setId(10L);
        room.setRoomNumber("101");
        Exam published = exam(1L, "Maths");
        Exam draft = exam(2L, "Physics");
        draft.setStatus(Exam.ExamStatus.DRAFT);
        Student a = student(1L, "101", "E");
        SeatAllocation publishedSeat = allocation(published, a, room, 1, 1);
        SeatAllocation draftSeat = allocation(draft, a, room, 2, 2);
        when(notificationService.notify(any(Student.class), any(Type.class), anyString(),
                anyString(), anyString(), anyLong(), anyString()))
                .thenReturn(new NotificationService.FanOutResult());

        examNotificationService.roomChanged(room, List.of(publishedSeat, draftSeat));

        verify(notificationService).notify(eq(a), eq(Type.ROOM_CHANGED), anyString(), anyString(),
                eq("seat"), eq(1L), anyString());
        // The draft exam's seating must not ping the student.
        verify(notificationService, never()).notify(any(Student.class), any(Type.class),
                anyString(), anyString(), anyString(), eq(2L), anyString());
    }

    @Test
    void allocationsWithoutAStatusAreSkipped() {
        Exam maths = exam(1L, "Maths");
        Room room = new Room();
        room.setId(10L);
        SeatAllocation draft = allocation(maths, student(1L, "101", "E"), room, 1, 1);
        draft.setStatus(SeatAllocation.Status.DRAFT);

        examNotificationService.seatingPublished(maths, new ArrayList<>(List.of(draft)));

        verify(notificationService, never()).notify(any(Student.class), any(Type.class),
                anyString(), anyString(), anyString(), anyLong(), anyString());
    }
}
