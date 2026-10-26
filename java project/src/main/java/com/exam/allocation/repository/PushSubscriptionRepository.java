package com.exam.allocation.repository;

import com.exam.allocation.model.PushSubscription;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
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

    // ------------------------------------------------- campaign / admin management

    /** Every live device - the audience of a broadcast campaign (cursor-batched by id). */
    List<PushSubscription> findByActiveTrueOrderByIdAsc();

    /** Paged admin listing, newest devices first. */
    Page<PushSubscription> findAllByOrderByIdDesc(Pageable pageable);

    /** Paged admin listing filtered by active state. */
    Page<PushSubscription> findByActiveOrderByIdDesc(boolean active, Pageable pageable);

    /** Paged admin listing for the devices of one student set (search by roll/name). */
    Page<PushSubscription> findByStudentIdInOrderByIdDesc(Collection<Long> studentIds, Pageable pageable);

    /** Devices of one student set and state, for search + status filter together. */
    Page<PushSubscription> findByStudentIdInAndActiveOrderByIdDesc(Collection<Long> studentIds,
                                                                   boolean active, Pageable pageable);

    /** Summary counters for the subscribers card. */
    long countByActiveTrue();

    /** Cleanup: hard-delete dead subscriptions past the retention window. */
    void deleteByActiveFalseAndCreatedAtBefore(Instant cutoff);
}
