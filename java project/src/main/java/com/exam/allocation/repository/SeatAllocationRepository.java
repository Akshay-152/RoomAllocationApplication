package com.exam.allocation.repository;

import com.exam.allocation.model.SeatAllocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface SeatAllocationRepository extends JpaRepository<SeatAllocation, Long> {
    List<SeatAllocation> findByExamId(Long examId);
    List<SeatAllocation> findByExamIdAndStatus(Long examId, SeatAllocation.Status status);
    List<SeatAllocation> findByRoomIdAndStatus(Long roomId, SeatAllocation.Status status);
    List<SeatAllocation> findByStudentId(Long studentId);
    List<SeatAllocation> findByStudentIdOrderByCreatedAtDesc(Long studentId);
    SeatAllocation findByExamIdAndStudentId(Long examId, Long studentId);
    long countByExamId(Long examId);
    long countByExamIdAndStatus(Long examId, SeatAllocation.Status status);
    @Transactional
    void deleteByExamId(Long examId);

    @Transactional
    void deleteByRoomId(Long roomId);
}
