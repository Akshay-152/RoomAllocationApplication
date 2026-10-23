package com.exam.allocation.service;

import com.exam.allocation.model.Exam;
import com.exam.allocation.model.ExamSemester;
import com.exam.allocation.repository.ExamSemesterRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Semester (1..8) audience for exams.
 *
 * <p>The authoritative assignment lives in the {@code exam_semesters} table
 * (an exam may target several semesters at once). For backward compatibility an
 * exam without rows falls back to its legacy single {@code exam.semester}
 * column, and an exam with neither applies to <b>every</b> semester - existing
 * records therefore keep behaving exactly as before, with no data migration.
 *
 * <p>A student whose own semester is null (legacy record) is treated as a
 * wildcard as well, so no existing student is silently excluded.
 */
@Service
public class ExamSemesterService {

    public static final int MIN_SEMESTER = 1;
    public static final int MAX_SEMESTER = 8;

    @Autowired
    private ExamSemesterRepository examSemesterRepository;

    /** Sorted semester numbers stored in exam_semesters for the given exam. */
    public List<Integer> storedSemesters(Long examId) {
        if (examId == null) return List.of();
        return examSemesterRepository.findByExamId(examId).stream()
                .map(ExamSemester::getSemesterNumber)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * The semesters the exam applies to: explicit rows first, otherwise the
     * legacy single column. An empty set means "every semester".
     */
    public Set<Integer> assignedSemesters(Exam exam) {
        if (exam == null) return Set.of();
        List<Integer> rows = storedSemesters(exam.getId());
        if (!rows.isEmpty()) return new LinkedHashSet<>(rows);
        if (exam.getSemester() != null) return new LinkedHashSet<>(List.of(exam.getSemester()));
        return Set.of();
    }

    /** True when the student's semester is covered (null on either side = wildcard). */
    public boolean matchesStudent(Exam exam, Integer studentSemester) {
        Set<Integer> assigned = assignedSemesters(exam);
        if (assigned.isEmpty()) return true;
        if (studentSemester == null) return true;
        return assigned.contains(studentSemester);
    }

    /** Validates one semester value; null is allowed (= not set). */
    public static void validateSemester(Integer semester) {
        if (semester != null && (semester < MIN_SEMESTER || semester > MAX_SEMESTER)) {
            throw new IllegalArgumentException("Semester must be between 1 and 8.");
        }
    }

    /** Validates a whole selection, keeping order and dropping duplicates. */
    public static List<Integer> validateSemesters(Collection<Integer> semesters) {
        List<Integer> out = new ArrayList<>();
        if (semesters == null) return out;
        for (Integer semester : semesters) {
            if (semester == null) {
                throw new IllegalArgumentException("Semesters must be whole numbers between 1 and 8.");
            }
            if (semester < MIN_SEMESTER || semester > MAX_SEMESTER) {
                throw new IllegalArgumentException(
                        "Semesters must be between 1 and 8 - got " + semester + ".");
            }
            if (!out.contains(semester)) out.add(semester);
        }
        return out;
    }

    /**
     * Replaces the exam's semester rows (empty selection = every semester) and
     * keeps the legacy single column in sync with the smallest selected value,
     * so older readers (PDF header, simple exam JSON) stay meaningful.
     */
    public List<Integer> replaceSemesters(Exam exam, Collection<Integer> semesters) {
        List<Integer> wanted = validateSemesters(semesters);
        examSemesterRepository.deleteByExamId(exam.getId());
        for (Integer semester : wanted) {
            examSemesterRepository.save(new ExamSemester(exam, semester));
        }
        exam.setSemester(wanted.isEmpty() ? null : wanted.get(0));
        return wanted;
    }

    /** Removes every semester row of an exam (used when an exam is deleted). */
    public void clear(Long examId) {
        if (examId != null) examSemesterRepository.deleteByExamId(examId);
    }
}
