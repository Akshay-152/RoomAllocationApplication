package com.exam.allocation.controller;

import com.exam.allocation.model.Exam;
import com.exam.allocation.model.SeatAllocation;
import com.exam.allocation.repository.ExamEligibilityRepository;
import com.exam.allocation.repository.ExamRepository;
import com.exam.allocation.repository.ExamRoomRepository;
import com.exam.allocation.repository.SeatAllocationRepository;
import com.exam.allocation.repository.SeatRepository;
import com.exam.allocation.service.AuditService;
import com.exam.allocation.service.ConflictService;
import com.exam.allocation.service.ExamNotificationService;
import com.exam.allocation.service.ExamSemesterService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyCollection;

/**
 * Behaviour of the exam CRUD/lifecycle endpoints: validation, the DRAFT/PUBLISHED
 * rules and the delete guard. The security rules themselves live in SecurityConfig
 * and are covered by the UI suites.
 */
@ExtendWith(MockitoExtension.class)
class ExamControllerTest {

    @Mock private ExamRepository examRepository;
    @Mock private SeatRepository seatRepository;
    @Mock private SeatAllocationRepository seatAllocationRepository;
    @Mock private ExamEligibilityRepository eligibilityRepository;
    @Mock private ExamRoomRepository examRoomRepository;
    @Mock private ConflictService conflictService;
    @Mock private ExamSemesterService examSemesterService;
    @Mock private ExamNotificationService examNotificationService;
    @Mock private AuditService auditService;

    @InjectMocks private ExamController controller;

    // ------------------------------------------------------------------ creation

    @Test
    void newExamIsAlwaysStoredAsDraft() {
        Exam input = new Exam();
        input.setSubjectName("  Data Structures  ");
        input.setStatus(Exam.ExamStatus.PUBLISHED);
        when(examRepository.save(any(Exam.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Exam saved = controller.addExam(input);

        assertEquals(Exam.ExamStatus.DRAFT, saved.getStatus());
        assertEquals("Data Structures", saved.getSubjectName());
        verify(auditService).record(anyString(), eq("EXAM_CREATED"), anyString(), anyString());
    }

    @Test
    void examWithoutASubjectNameIsRejected() {
        Exam input = new Exam();
        input.setSubjectName("   ");

        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> controller.addExam(input));

        assertEquals("Subject name is required.", error.getMessage());
        verify(examRepository, never()).save(any(Exam.class));
    }

    @Test
    void endTimeMustBeAfterTheStartTime() {
        Exam input = new Exam();
        input.setSubjectName("Physics");
        input.setExamTime(LocalTime.of(11, 0));
        input.setEndTime(LocalTime.of(9, 0));

        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> controller.addExam(input));

        assertEquals("The end time must be after the start time.", error.getMessage());
        verify(examRepository, never()).save(any(Exam.class));
    }

    @Test
    void semesterOutsideOneToEightIsRejected() {
        Exam input = new Exam();
        input.setSubjectName("Chemistry");
        input.setSemester(9);

        assertThrows(IllegalArgumentException.class, () -> controller.addExam(input));
        verify(examRepository, never()).save(any(Exam.class));
    }

    @Test
    void semesterSelectionOutsideOneToEightIsRejected() {
        Exam input = new Exam();
        input.setSubjectName("Chemistry");
        input.setSemesters(new LinkedHashSet<>(List.of(2, 9)));

        assertThrows(IllegalArgumentException.class, () -> controller.addExam(input));
        verify(examRepository, never()).save(any(Exam.class));
    }

    @Test
    void creatingAnExamStoresItsSemesterSelection() {
        Exam input = new Exam();
        input.setSubjectName("Data Structures");
        input.setSemesters(new LinkedHashSet<>(List.of(1, 3)));
        when(examRepository.save(any(Exam.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Exam saved = controller.addExam(input);

        verify(examSemesterService).replaceSemesters(eq(saved), anyCollection());
        verify(auditService).record(anyString(), eq("EXAM_CREATED"), anyString(), anyString());
    }

    @Test
    void editingAnExamWithoutTheSemestersFieldKeepsTheCurrentAssignment() {
        Exam existing = published(5L, LocalDate.of(2026, 3, 2), LocalTime.of(9, 0));
        when(examRepository.findById(5L)).thenReturn(Optional.of(existing));
        when(examRepository.save(any(Exam.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Exam input = new Exam();
        input.setSubjectName("Mathematics");
        input.setExamDate(LocalDate.of(2026, 3, 2));
        input.setExamTime(LocalTime.of(9, 0));

        controller.updateExam(5L, input);

        verify(examSemesterService, never()).replaceSemesters(any(Exam.class), anyCollection());
    }

    // ------------------------------------------------------------------ editing

    @Test
    void editingTheScheduleOfAPublishedExamReturnsItToDraft() {
        Exam existing = published(5L, LocalDate.of(2026, 3, 2), LocalTime.of(9, 0));
        when(examRepository.findById(5L)).thenReturn(Optional.of(existing));
        when(examRepository.save(any(Exam.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Exam input = new Exam();
        input.setSubjectName("Mathematics");
        input.setExamDate(LocalDate.of(2026, 3, 9));
        input.setExamTime(LocalTime.of(9, 0));

        Exam saved = controller.updateExam(5L, input);

        assertEquals(Exam.ExamStatus.DRAFT, saved.getStatus());
        assertEquals(LocalDate.of(2026, 3, 9), saved.getExamDate());
        verify(auditService).record(anyString(), eq("EXAM_UPDATED"), eq("exam:5"), anyString());
    }

    @Test
    void editingOnlyTheDetailsKeepsAPublishedExamPublished() {
        Exam existing = published(5L, LocalDate.of(2026, 3, 2), LocalTime.of(9, 0));
        when(examRepository.findById(5L)).thenReturn(Optional.of(existing));
        when(examRepository.save(any(Exam.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Exam input = new Exam();
        input.setSubjectName("Mathematics");
        input.setExamDate(LocalDate.of(2026, 3, 2));
        input.setExamTime(LocalTime.of(9, 0));
        input.setExamType("Supplementary");

        Exam saved = controller.updateExam(5L, input);

        assertEquals(Exam.ExamStatus.PUBLISHED, saved.getStatus());
        assertEquals("Supplementary", saved.getExamType());
    }

    // ------------------------------------------------------------------ lifecycle

    @Test
    void publishingRequiresADateAndStartTime() {
        Exam draft = new Exam();
        draft.setId(1L);
        draft.setSubjectName("Physics");
        draft.setStatus(Exam.ExamStatus.DRAFT);
        when(examRepository.findById(1L)).thenReturn(Optional.of(draft));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> controller.setExamStatus(1L, Map.of("status", "PUBLISHED")));

        assertEquals("Set an exam date and start time before publishing.", error.getMessage());
        verify(examRepository, never()).save(any(Exam.class));
    }

    @Test
    void cancellingAnExamHidesItsPublishedSeats() {
        Exam exam = published(1L, LocalDate.of(2026, 3, 2), LocalTime.of(9, 0));
        when(examRepository.findById(1L)).thenReturn(Optional.of(exam));
        SeatAllocation allocation = new SeatAllocation();
        allocation.setStatus(SeatAllocation.Status.PUBLISHED);
        when(seatAllocationRepository.findByExamIdAndStatus(1L, SeatAllocation.Status.PUBLISHED))
                .thenReturn(new ArrayList<>(List.of(allocation)));

        Exam result = controller.setExamStatus(1L, Map.of("status", "cancelled"));

        assertEquals(Exam.ExamStatus.CANCELLED, result.getStatus());
        assertEquals(SeatAllocation.Status.DRAFT, allocation.getStatus());
        verify(seatAllocationRepository).saveAll(any());
        // Cancelling must notify the exam's eligible audience.
        verify(examNotificationService).examCancelled(exam);
    }

    @Test
    void publishingAnExamNotifiesItsEligibleAudience() {
        Exam exam = published(1L, LocalDate.of(2026, 3, 2), LocalTime.of(9, 0));
        exam.setStatus(Exam.ExamStatus.DRAFT);
        when(examRepository.findById(1L)).thenReturn(Optional.of(exam));

        Exam result = controller.setExamStatus(1L, Map.of("status", "PUBLISHED"));

        assertEquals(Exam.ExamStatus.PUBLISHED, result.getStatus());
        verify(examNotificationService).examPublished(exam);
    }

    @Test
    void anUnknownStatusIsRejected() {
        Exam exam = published(1L, LocalDate.of(2026, 3, 2), LocalTime.of(9, 0));
        when(examRepository.findById(1L)).thenReturn(Optional.of(exam));

        assertThrows(IllegalArgumentException.class,
                () -> controller.setExamStatus(1L, Map.of("status", "archived")));
    }

    // ------------------------------------------------------------------ deletion

    @Test
    void deletingAPublishedExamIsRefused() {
        Exam exam = published(7L, LocalDate.of(2026, 3, 2), LocalTime.of(9, 0));
        when(examRepository.findById(7L)).thenReturn(Optional.of(exam));

        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> controller.deleteExam(7L));

        assertEquals("Cancel the exam before deleting it - it is currently published to students.",
                error.getMessage());
        verify(seatAllocationRepository, never()).deleteByExamId(anyLong());
        verify(examRepository, never()).deleteById(anyLong());
    }

    @Test
    void deletingADraftExamAlsoClearsItsSeatData() {
        Exam exam = new Exam();
        exam.setId(9L);
        exam.setSubjectName("Physics");
        exam.setStatus(Exam.ExamStatus.DRAFT);
        when(examRepository.findById(9L)).thenReturn(Optional.of(exam));
        when(seatRepository.findByExamId(9L)).thenReturn(List.of());

        Map<String, Object> result = controller.deleteExam(9L);

        assertEquals(Boolean.TRUE, result.get("deleted"));
        verify(seatAllocationRepository).deleteByExamId(9L);
        verify(eligibilityRepository).deleteByExamId(9L);
        // Rooms remembered for a generated chart must be cleared too, otherwise
        // their FK to the exam turns the delete into a data conflict.
        verify(examRoomRepository).deleteByExamId(9L);
        verify(examRepository).deleteById(9L);
        verify(auditService).record(anyString(), eq("EXAM_DELETED"), eq("exam:9"), anyString());
    }

    // ------------------------------------------------------------------ listing

    @Test
    void listingFiltersByStatusAndRejectsUnknownOnes() {
        Exam draft = new Exam();
        draft.setId(1L);
        draft.setSubjectName("Draft exam");
        draft.setStatus(Exam.ExamStatus.DRAFT);
        Exam done = published(2L, LocalDate.of(2026, 1, 5), LocalTime.of(10, 0));
        when(examRepository.findAll()).thenReturn(new ArrayList<>(List.of(draft, done)));

        assertEquals(List.of(done), controller.getAllExams("published"));
        assertThrows(IllegalArgumentException.class, () -> controller.getAllExams("archived"));
    }

    // ------------------------------------------------------------------ semesters

    @Test
    void settingSemestersValidatesAuditsAndReturnsTheSavedSelection() {
        Exam exam = published(3L, LocalDate.of(2026, 3, 2), LocalTime.of(9, 0));
        when(examRepository.findById(3L)).thenReturn(Optional.of(exam));
        when(examRepository.save(any(Exam.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(examSemesterService.assignedSemesters(exam)).thenReturn(new LinkedHashSet<>(List.of(2, 5)));

        List<Integer> saved = controller.setSemesters(3L, Map.of("semesters", List.of(2, 5, 2)));

        assertEquals(List.of(2, 5), saved);   // duplicates removed, order kept
        verify(examSemesterService).replaceSemesters(eq(exam), anyCollection());
        verify(auditService).record(anyString(), eq("EXAM_SEMESTERS"), eq("exam:3"), anyString());
    }

    @Test
    void settingAnOutOfRangeSemesterIsRejected() {
        Exam exam = published(3L, LocalDate.of(2026, 3, 2), LocalTime.of(9, 0));
        when(examRepository.findById(3L)).thenReturn(Optional.of(exam));

        assertThrows(IllegalArgumentException.class,
                () -> controller.setSemesters(3L, Map.of("semesters", List.of(0))));
        assertThrows(IllegalArgumentException.class,
                () -> controller.setSemesters(3L, Map.of("semesters", List.of(9))));
        verify(examSemesterService, never()).replaceSemesters(any(Exam.class), anyCollection());
    }

    @Test
    void deletingAnExamAlsoClearsItsSemesterRows() {
        Exam exam = new Exam();
        exam.setId(9L);
        exam.setSubjectName("Physics");
        exam.setStatus(Exam.ExamStatus.DRAFT);
        when(examRepository.findById(9L)).thenReturn(Optional.of(exam));
        when(seatRepository.findByExamId(9L)).thenReturn(List.of());

        controller.deleteExam(9L);

        verify(examSemesterService).clear(9L);
    }

    private Exam published(Long id, LocalDate date, LocalTime time) {
        Exam exam = new Exam();
        exam.setId(id);
        exam.setSubjectName("Mathematics");
        exam.setExamDate(date);
        exam.setExamTime(time);
        exam.setStatus(Exam.ExamStatus.PUBLISHED);
        return exam;
    }
}
