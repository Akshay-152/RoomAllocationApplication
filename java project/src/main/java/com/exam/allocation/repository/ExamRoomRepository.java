package com.exam.allocation.repository;

import com.exam.allocation.model.ExamRoom;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface ExamRoomRepository extends JpaRepository<ExamRoom, Long> {
    List<ExamRoom> findByExamId(Long examId);
    List<ExamRoom> findByRoomId(Long roomId);
    @Transactional
    void deleteByExamId(Long examId);

    /** Clears the exam↔room references so a room can be deleted after it was used in an allocation. */
    @Transactional
    void deleteByRoomId(Long roomId);
}
