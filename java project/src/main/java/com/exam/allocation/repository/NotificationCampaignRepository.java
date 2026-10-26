package com.exam.allocation.repository;

import com.exam.allocation.model.NotificationCampaign;
import com.exam.allocation.model.NotificationCampaign.Status;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

@Repository
public interface NotificationCampaignRepository extends JpaRepository<NotificationCampaign, Long> {

    /** Paged campaign history with optional title/body search. */
    Page<NotificationCampaign> findByTitleContainingIgnoreCaseOrBodyContainingIgnoreCase(
            String title, String body, Pageable pageable);

    /** Paged campaign history without a search filter. */
    Page<NotificationCampaign> findAllBy(Pageable pageable);

    /** Scheduled campaigns whose fire time has passed (restart-safe scheduler). */
    List<NotificationCampaign> findByStatusAndScheduledAtBefore(Status status, Instant now);

    /** Subscriber/campaign summary counts for the dashboard header. */
    long countByStatusIn(Collection<Status> statuses);
}
