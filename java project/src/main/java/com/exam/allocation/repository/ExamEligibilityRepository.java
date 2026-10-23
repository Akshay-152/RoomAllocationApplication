package com.exam.allocation.repository;

import com.exam.allocation.model.ExamEligibility;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface ExamEligibilityRepository extends JpaRepository<ExamEligibility, Long> {
    List<ExamEligibility> findByExamId(Long examId);

    @Transactional
    void deleteByExamId(Long examId);
}
