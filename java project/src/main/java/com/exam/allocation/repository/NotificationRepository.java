package com.exam.allocation.repository;

import com.exam.allocation.model.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.exam.allocation.model.Notification.Type;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /** Newest-first inbox of one student. */
    List<Notification> findByStudentIdOrderByCreatedAtDesc(Long studentId);

    long countByStudentIdAndReadAtIsNull(Long studentId);

    /** Idempotency guard: has this student already received this event? */
    boolean existsByStudentIdAndDedupeKey(Long studentId, String dedupeKey);

    /** Newest seat-related notification of one student for one exam (change detection). */
    Optional<Notification> findTopByStudentIdAndExamIdAndTypeInOrderByCreatedAtDesc(
            Long studentId, Long examId, Set<Type> types);

    /** Ownership check + fetch in one step: never returns another student's row. */
    Optional<Notification> findByIdAndStudentId(Long id, Long studentId);

    @Modifying
    @Query("update Notification n set n.readAt = :now "
            + "where n.studentId = :studentId and n.readAt is null")
    int markAllRead(@Param("studentId") Long studentId, @Param("now") Instant now);

    /** Student deletion cleans up their notification history. */
    void deleteByStudentId(Long studentId);
}
