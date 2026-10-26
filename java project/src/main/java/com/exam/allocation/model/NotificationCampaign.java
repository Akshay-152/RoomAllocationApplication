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
import java.time.Instant;

/**
 * One administrator-authored broadcast campaign ({@code notification_campaigns}
 * table) - the merged-in equivalent of the standalone notification tooling's
 * campaigns: a title/message pushed to <b>every</b> registered device, composed
 * in advance, kept as a draft, scheduled for later or sent immediately.
 *
 * <p>Lifecycle ({@link Status}):
 * <ul>
 *   <li>{@code DRAFT} - saved wording, nothing sent; editable and deletable;</li>
 *   <li>{@code QUEUED} - waiting for the send engine (immediate send);</li>
 *   <li>{@code SCHEDULED} - fires automatically at {@code scheduledAt}; the
 *       schedule lives in the database, so it survives restarts;</li>
 *   <li>{@code SENDING} - delivery in progress (cancellable between batches);</li>
 *   <li>{@code SENT / PARTIALLY_FAILED / FAILED} - terminal delivery outcomes;</li>
 *   <li>{@code CANCELLED} - stopped before or during the send.</li>
 * </ul>
 *
 * <p>Targeted per-student messages remain the job of {@link Announcement} plus
 * the per-event notification fan-out; campaigns are the broadcast channel.
 */
@Entity
@Table(name = "notification_campaigns", indexes = {
        @Index(name = "idx_campaigns_status", columnList = "status"),
        @Index(name = "idx_campaigns_scheduled", columnList = "scheduled_at")
})
public class NotificationCampaign {

    public enum Status { DRAFT, QUEUED, SCHEDULED, SENDING, SENT, PARTIALLY_FAILED, FAILED, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Push title, max 200 characters (same limit as a notification row). */
    @Column(nullable = false, length = 200)
    private String title;

    /** Push body text, max 1000 characters. */
    @Column(nullable = false, length = 1000)
    private String body;

    /**
     * Where a click on the push opens: an in-app section link
     * ({@code "/index.html#overview"}) or an absolute URL on any origin
     * (validated at compose time; the service worker still refuses to leave
     * its own origin, so only same-origin paths actually navigate).
     */
    @Column(name = "destination_url", length = 500)
    private String destinationUrl;

    /** Optional notification icon URL (relative or absolute); blank = app icon. */
    @Column(length = 500)
    private String icon;

    /** Optional push tag: browsers replace an existing visible notification with the same tag. */
    @Column(length = 64)
    private String tag;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.DRAFT;

    /** Fire time for SCHEDULED campaigns (null otherwise). */
    private Instant scheduledAt;

    /** Set while SENDING to stop before the next batch; the engine then finishes as CANCELLED. */
    @Column(name = "cancel_requested", nullable = false)
    private boolean cancelRequested;

    /** Administrator username that authored the campaign. */
    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    public NotificationCampaign() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }

    public String getDestinationUrl() { return destinationUrl; }
    public void setDestinationUrl(String destinationUrl) { this.destinationUrl = destinationUrl; }

    public String getIcon() { return icon; }
    public void setIcon(String icon) { this.icon = icon; }

    public String getTag() { return tag; }
    public void setTag(String tag) { this.tag = tag; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public Instant getScheduledAt() { return scheduledAt; }
    public void setScheduledAt(Instant scheduledAt) { this.scheduledAt = scheduledAt; }

    public boolean isCancelRequested() { return cancelRequested; }
    public void setCancelRequested(boolean cancelRequested) { this.cancelRequested = cancelRequested; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
