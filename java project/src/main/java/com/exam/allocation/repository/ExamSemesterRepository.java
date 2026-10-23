package com.exam.allocation.repository;

import com.exam.allocation.model.ExamSemester;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface ExamSemesterRepository extends JpaRepository<ExamSemester, Long> {
    List<ExamSemester> findByExamId(Long examId);

    @Transactional
    void deleteByExamId(Long examId);
}
