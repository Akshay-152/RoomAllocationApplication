package com.exam.allocation.repository;

import com.exam.allocation.model.Seat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SeatRepository extends JpaRepository<Seat, Long> {
    List<Seat> findByRoomId(Long roomId);
    List<Seat> findByExamId(Long examId);
    List<Seat> findByAllocatedStudentId(Long studentId);
}
