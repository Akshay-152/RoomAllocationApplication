package com.exam.allocation.service;

import com.exam.allocation.model.Exam;
import com.exam.allocation.repository.ExamRepository;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Scheduling-clash rules: two exams clash only when they run on the same date at
 * the same time for an overlapping audience (blank branch/batch = wildcard).
 */
@ExtendWith(MockitoExtension.class)
class ConflictServiceTest {

    @Mock private ExamRepository examRepository;
    @Mock private ExamSemesterService examSemesterService;

    @InjectMocks private ConflictService conflictService;

    private static final LocalDate DAY = LocalDate.of(2026, 4, 20);
    private static final LocalTime TEN = LocalTime.of(10, 0);

    @Test
    void sameDateAndTimeForAnOverlappingAudienceClashes() {
        assertTrue(conflictService.isSameSlot(exam(1L, DAY, TEN, "CEC", "E"),
                exam(2L, DAY, TEN, "CEC", "E")));
    }

    @Test
    void aDifferentTimeOrDateDoesNotClash() {
        assertFalse(conflictService.isSameSlot(exam(1L, DAY, TEN, "CEC", "E"),
                exam(2L, DAY, LocalTime.of(14, 0), "CEC", "E")));
        assertFalse(conflictService.isSameSlot(exam(1L, DAY, TEN, "CEC", "E"),
                exam(2L, DAY.plusDays(1), TEN, "CEC", "E")));
    }

    @Test
    void aBlankBranchOrBatchActsAsAWildcard() {
        assertTrue(conflictService.isSameSlot(exam(1L, DAY, TEN, null, null),
                exam(2L, DAY, TEN, "CEC", "E")));
        assertTrue(conflictService.isSameSlot(exam(1L, DAY, TEN, "All", "all"),
                exam(2L, DAY, TEN, "CEC", "E")));
    }

    @Test
    void differentAudiencesDoNotClash() {
        assertFalse(conflictService.isSameSlot(exam(1L, DAY, TEN, "CEC", "E"),
                exam(2L, DAY, TEN, "CEC", "F")));
        assertFalse(conflictService.isSameSlot(exam(1L, DAY, TEN, "CEC", "E"),
                exam(2L, DAY, TEN, "MPC", "E")));
    }

    @Test
    void examsForDisjointSemestersDoNotClash() {
        Exam first = exam(1L, DAY, TEN, "CEC", "E");
        Exam second = exam(2L, DAY, TEN, "CEC", "E");
        when(examSemesterService.assignedSemesters(first)).thenReturn(new LinkedHashSet<>(List.of(1)));
        when(examSemesterService.assignedSemesters(second)).thenReturn(new LinkedHashSet<>(List.of(2)));

        assertFalse(conflictService.isSameSlot(first, second));
    }

    @Test
    void examsSharingAtLeastOneSemesterClash() {
        Exam first = exam(1L, DAY, TEN, "CEC", "E");
        Exam second = exam(2L, DAY, TEN, "CEC", "E");
        when(examSemesterService.assignedSemesters(first)).thenReturn(new LinkedHashSet<>(List.of(2, 3)));
        when(examSemesterService.assignedSemesters(second)).thenReturn(new LinkedHashSet<>(List.of(3, 4)));

        assertTrue(conflictService.isSameSlot(first, second));
    }

    @Test
    void anExamWithoutSemesterAssignmentClashesWithAnySemester() {
        Exam open = exam(1L, DAY, TEN, "CEC", "E");
        Exam targeted = exam(2L, DAY, TEN, "CEC", "E");
        when(examSemesterService.assignedSemesters(open)).thenReturn(Set.of());
        when(examSemesterService.assignedSemesters(targeted)).thenReturn(new LinkedHashSet<>(List.of(5)));

        assertTrue(conflictService.isSameSlot(open, targeted));
    }

    @Test
    void anExamNeverClashesWithItself() {
        Exam exam = exam(1L, DAY, TEN, "CEC", "E");
        assertFalse(conflictService.isSameSlot(exam, exam));
    }

    @Test
    void findConflictsReportsEveryClashingPairOnce() {
        Exam first = exam(1L, DAY, TEN, "CEC", "E");
        Exam second = exam(2L, DAY, TEN, "CEC", "E");
        Exam unrelated = exam(3L, DAY, LocalTime.of(14, 0), "CEC", "E");
        when(examRepository.findAll()).thenReturn(new ArrayList<>(List.of(first, second, unrelated)));

        List<ConflictService.Conflict> conflicts = conflictService.findConflicts();

        assertEquals(1, conflicts.size());
        ConflictService.Conflict conflict = conflicts.get(0);
        assertEquals("SAME_SLOT", conflict.getType());
        assertEquals(1L, conflict.getExamId());
        assertEquals(2L, conflict.getOtherExamId());
        assertTrue(conflict.getMessage().contains("Mathematics"));
    }

    @Test
    void findConflictsForOnlyReturnsClashesTouchingTheGivenExams() {
        Exam a = exam(1L, DAY, TEN, "CEC", "E");
        Exam b = exam(2L, DAY, TEN, "CEC", "E");
        Exam c = exam(3L, DAY.plusDays(2), TEN, "MPC", "F");
        Exam d = exam(4L, DAY.plusDays(2), TEN, "MPC", "F");
        when(examRepository.findAll()).thenReturn(new ArrayList<>(List.of(a, b, c, d)));

        List<ConflictService.Conflict> conflicts = conflictService.findConflictsFor(Set.of(3L));

        assertEquals(1, conflicts.size());
        assertEquals(3L, conflicts.get(0).getExamId());
        assertEquals(4L, conflicts.get(0).getOtherExamId());
    }

    private Exam exam(Long id, LocalDate date, LocalTime time, String branch, String course) {
        Exam exam = new Exam();
        exam.setId(id);
        exam.setSubjectName("Mathematics");
        exam.setExamDate(date);
        exam.setExamTime(time);
        exam.setBranch(branch);
        exam.setCourse(course);
        return exam;
    }
}
