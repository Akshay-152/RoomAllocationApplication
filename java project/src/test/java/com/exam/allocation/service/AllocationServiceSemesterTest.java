package com.exam.allocation.service;

import com.exam.allocation.model.Exam;
import com.exam.allocation.model.ExamSemester;
import com.exam.allocation.model.Student;
import com.exam.allocation.repository.ExamEligibilityRepository;
import com.exam.allocation.repository.ExamSemesterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;

/**
 * The exam/student audience rules around semesters: an exam published to one or
 * more semesters only matches students of those semesters, legacy records (no
 * semester anywhere) keep matching everything, and the legacy single-semester
 * column is still honoured.
 */
@ExtendWith(MockitoExtension.class)
class AllocationServiceSemesterTest {

    @Mock private ExamEligibilityRepository examEligibilityRepository;
    @Mock private ExamSemesterRepository examSemesterRepository;

    @InjectMocks private AllocationService allocationService;

    private ExamSemesterService examSemesterService;

    @BeforeEach
    void wireTheRealSemesterService() {
        // The real service (with a mocked repository) so matchesStudent() is exercised end to end.
        examSemesterService = new ExamSemesterService();
        ReflectionTestUtils.setField(examSemesterService, "examSemesterRepository", examSemesterRepository);
        ReflectionTestUtils.setField(allocationService, "examSemesterService", examSemesterService);
    }

    @Test
    void aStudentOfAPublishedSemesterMatchesTheExam() {
        Exam exam = examWithSemesters(2, 3);
        Student student = student("101", "CEC", "E", 3);

        assertTrue(allocationService.matchesExam(student, exam));
    }

    @Test
    void aStudentOfAnotherSemesterDoesNotMatchTheExam() {
        Exam exam = examWithSemesters(2, 3);
        Student student = student("101", "CEC", "E", 4);

        assertFalse(allocationService.matchesExam(student, exam));
    }

    @Test
    void aStudentWithoutASemesterStillMatchesLegacyCompatibility() {
        Exam exam = examWithSemesters(2);
        Student student = student("101", "CEC", "E", null);

        assertTrue(allocationService.matchesExam(student, exam));
    }

    @Test
    void anExamWithoutAnySemesterAssignmentMatchesEverySemester() {
        Exam exam = examWithSemesters();
        Student student = student("101", "CEC", "E", 6);

        assertTrue(allocationService.matchesExam(student, exam));
    }

    @Test
    void theLegacySingleSemesterColumnStillFiltersWhenNoRowsExist() {
        Exam exam = examWithSemesters();
        exam.setSemester(4);

        assertTrue(allocationService.matchesExam(student("101", "CEC", "E", 4), exam));
        assertFalse(allocationService.matchesExam(student("102", "CEC", "E", 5), exam));
    }

    @Test
    void batchAndSemesterAreBothEnforced() {
        Exam exam = examWithSemesters(2);
        exam.setCourse("E");

        assertFalse(allocationService.matchesExam(student("101", "CEC", "F", 2), exam));
        assertFalse(allocationService.matchesExam(student("102", "CEC", "E", 3), exam));
        assertTrue(allocationService.matchesExam(student("103", "CEC", "E", 2), exam));
    }

    // ------------------------------------------------------------------ helpers

    private Exam examWithSemesters(Integer... semesters) {
        Exam exam = new Exam();
        exam.setId(1L);
        exam.setSubjectName("Data Structures");
        exam.setBranch("CEC");
        if (semesters.length > 0) {
            List<ExamSemester> rows = new java.util.ArrayList<>();
            for (Integer semester : semesters) rows.add(new ExamSemester(exam, semester));
            lenient().when(examSemesterRepository.findByExamId(1L)).thenReturn(rows);
        } else {
            lenient().when(examSemesterRepository.findByExamId(1L)).thenReturn(List.of());
        }
        lenient().when(examEligibilityRepository.findByExamId(1L)).thenReturn(List.of());
        return exam;
    }

    private Student student(String rollNo, String branch, String batch, Integer semester) {
        Student student = new Student();
        student.setRollNo(rollNo);
        student.setBranch(branch);
        student.setBatch(batch);
        student.setSemester(semester);
        return student;
    }
}
