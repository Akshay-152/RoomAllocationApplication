package com.exam.allocation.repository;

import com.exam.allocation.model.CampaignDelivery;
import com.exam.allocation.model.CampaignDelivery.Result;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CampaignDeliveryRepository extends JpaRepository<CampaignDelivery, Long> {

    /** Paginated per-device results for one campaign (newest first). */
    Page<CampaignDelivery> findByCampaignIdOrderByIdDesc(Long campaignId, Pageable pageable);

    /** Same list filtered by outcome (accepted / failed / skipped). */
    Page<CampaignDelivery> findByCampaignIdAndResultOrderByIdDesc(Long campaignId, Result result,
                                                                  Pageable pageable);

    /** One row per (campaign, subscription): a re-send updates instead of appending. */
    Optional<CampaignDelivery> findByCampaignIdAndSubscriptionId(Long campaignId, Long subscriptionId);

    /** Progress counters for the campaign list and the live progress bar. */
    long countByCampaignIdAndResult(Long campaignId, Result result);
}
