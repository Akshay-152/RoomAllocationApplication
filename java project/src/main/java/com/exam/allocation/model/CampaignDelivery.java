package com.exam.allocation.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/**
 * One delivery attempt of one campaign to one subscriber
 * ({@code campaign_deliveries} table) - the audit trail behind the campaign
 * progress bar and its per-device results view.
 *
 * <p>Exactly one row per (campaign, subscription); a re-send updates the row
 * rather than appending. {@code subscriptionId} is nullable and set to NULL
 * when the subscription is later cleaned up, so history survives deletions.
 */
@Entity
@Table(name = "campaign_deliveries",
        uniqueConstraints = @UniqueConstraint(name = "uk_campaign_delivery",
                columnNames = {"campaign_id", "subscription_id"}),
        indexes = {
                @Index(name = "idx_campaign_deliveries_campaign", columnList = "campaign_id"),
                @Index(name = "idx_campaign_deliveries_subscription", columnList = "subscription_id")
        })
public class CampaignDelivery {

    public enum Result { ACCEPTED, FAILED, SKIPPED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "campaign_id", nullable = false)
    private Long campaignId;

    /** Nullable on purpose: survives cleanup of the subscription row. */
    @Column(name = "subscription_id")
    private Long subscriptionId;

    @Column(name = "attempt_at", nullable = false)
    private Instant attemptAt = Instant.now();

    /** How many tries the push step needed (1 = first try accepted/failed). */
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 1;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Result result;

    /** HTTP status the push service answered with, when there was an answer. */
    @Column(name = "http_status")
    private Integer httpStatus;

    /** Sanitized error message; never contains key material. */
    @Column(name = "error_message", length = 300)
    private String errorMessage;

    public CampaignDelivery() {}

    public CampaignDelivery(Long campaignId, Long subscriptionId, Result result) {
        this.campaignId = campaignId;
        this.subscriptionId = subscriptionId;
        this.result = result;
        this.attemptAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getCampaignId() { return campaignId; }
    public void setCampaignId(Long campaignId) { this.campaignId = campaignId; }

    public Long getSubscriptionId() { return subscriptionId; }
    public void setSubscriptionId(Long subscriptionId) { this.subscriptionId = subscriptionId; }

    public Instant getAttemptAt() { return attemptAt; }
    public void setAttemptAt(Instant attemptAt) { this.attemptAt = attemptAt; }

    public int getAttemptCount() { return attemptCount; }
    public void setAttemptCount(int attemptCount) { this.attemptCount = attemptCount; }

    public Result getResult() { return result; }
    public void setResult(Result result) { this.result = result; }

    public Integer getHttpStatus() { return httpStatus; }
    public void setHttpStatus(Integer httpStatus) { this.httpStatus = httpStatus; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
