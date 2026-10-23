package com.exam.allocation.repository;

import com.exam.allocation.model.PushSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, Long> {

    /** Active devices of one student (the send target list). */
    List<PushSubscription> findByStudentIdAndActiveTrue(Long studentId);

    /** One browser subscription is unique across all students (endpoint identity). */
    Optional<PushSubscription> findByEndpoint(String endpoint);

    /** Ownership check + fetch in one step: never returns another student's row. */
    Optional<PushSubscription> findByIdAndStudentId(Long id, Long studentId);

    /** Student deletion also removes their device registrations. */
    void deleteByStudentId(Long studentId);
}
