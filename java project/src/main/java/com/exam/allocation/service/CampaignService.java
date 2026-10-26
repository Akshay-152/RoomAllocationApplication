package com.exam.allocation.service;

import com.exam.allocation.model.CampaignDelivery;
import com.exam.allocation.model.NotificationCampaign;
import com.exam.allocation.model.NotificationCampaign.Status;
import com.exam.allocation.model.PushSubscription;
import com.exam.allocation.model.Student;
import com.exam.allocation.repository.CampaignDeliveryRepository;
import com.exam.allocation.repository.NotificationCampaignRepository;
import com.exam.allocation.repository.PushSubscriptionRepository;
import com.exam.allocation.repository.StudentRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Broadcast campaigns: the merged-in half of the standalone notification
 * tooling, running inside this application on the same database and port.
 *
 * <p>An admin composes one title/message push and sends it to <b>every</b>
 * registered device - immediately, scheduled for later, or saved as a draft.
 * Targeted per-student messages stay with {@link NotificationService} /
 * announcements; this service is the all-subscribers channel.
 *
 * <p>Guarantees (mirroring the reference implementation):
 * <ul>
 *   <li><b>Restart-safe scheduling</b> - the schedule lives in the database;
 *       {@link #pollDueScheduled()} picks up anything that came due while the
 *       server was down;</li>
 *   <li><b>No double sends</b> - an in-flight guard plus the status check means
 *       a double click or a scheduler tick during a manual send cannot run the
 *       same campaign twice;</li>
 *   <li><b>Cancellable while sending</b> - the engine stops before the next batch;</li>
 *   <li><b>Bounded work</b> - devices are delivered in batches of 200 on a
 *       dedicated single-thread executor, never on a request thread;</li>
 *   <li><b>Full audit</b> - one delivery row per device with result, HTTP status
 *       and sanitized error, plus an audit-log entry per campaign action.</li>
 * </ul>
 */
@Service
public class CampaignService {

    private static final Logger log = LoggerFactory.getLogger(CampaignService.class);

    /** Devices per delivery batch (kept bounded, like the reference tooling). */
    static final int BATCH_SIZE = 200;

    /** Inactive subscriptions older than this are removed by Cleanup. */
    static final int INACTIVE_RETENTION_DAYS = 30;

    /** Statuses an admin may still edit or delete. */
    private static final Set<Status> EDITABLE = Set.of(Status.DRAFT, Status.QUEUED, Status.SCHEDULED);

    private final NotificationCampaignRepository campaigns;
    private final CampaignDeliveryRepository deliveries;
    private final PushSubscriptionRepository subscriptions;
    private final StudentRepository students;
    private final WebPushService webPushService;
    private final AuditService auditService;
    private final TransactionTemplate transactions;

    /** Campaign ids currently being sent in this JVM (status guard is in the DB). */
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    private final ExecutorService sendExecutor =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "campaign-sender");
                thread.setDaemon(true);
                return thread;
            });

    public CampaignService(NotificationCampaignRepository campaigns,
                           CampaignDeliveryRepository deliveries,
                           PushSubscriptionRepository subscriptions,
                           StudentRepository students,
                           WebPushService webPushService,
                           AuditService auditService,
                           TransactionTemplate transactions) {
        this.campaigns = campaigns;
        this.deliveries = deliveries;
        this.subscriptions = subscriptions;
        this.students = students;
        this.webPushService = webPushService;
        this.auditService = auditService;
        this.transactions = transactions;
    }

    @PreDestroy
    void shutdown() {
        sendExecutor.shutdownNow();
    }

    // ------------------------------------------------------------------ compose

    /**
     * Creates a campaign from validated input. {@code scheduledAt} in the future
     * registers it with the persistent scheduler; {@code draft} saves it without
     * queueing anything; otherwise it is queued for an immediate send.
     */
    public NotificationCampaign create(String title, String body, String destinationUrl,
                                       String icon, String tag, Instant scheduledAt,
                                       boolean draft, String actor) {
        NotificationCampaign campaign = new NotificationCampaign();
        campaign.setTitle(truncate(title, 200));
        campaign.setBody(truncate(body == null ? "" : body, 1000));
        campaign.setDestinationUrl(truncate(destinationUrl, 500));
        campaign.setIcon(truncate(icon, 500));
        campaign.setTag(truncate(tag, 64));
        campaign.setCreatedBy(actor);
        if (draft) {
            campaign.setStatus(Status.DRAFT);
        } else if (scheduledAt != null) {
            campaign.setStatus(Status.SCHEDULED);
            campaign.setScheduledAt(scheduledAt);
        } else {
            campaign.setStatus(Status.QUEUED);
        }
        NotificationCampaign saved = campaigns.save(campaign);
        auditService.record(actor, draft ? "CAMPAIGN_DRAFTED"
                        : scheduledAt != null ? "CAMPAIGN_SCHEDULED" : "CAMPAIGN_CREATED",
                "campaign:" + saved.getId(), saved.getTitle());
        if (saved.getStatus() == Status.QUEUED) {
            startSend(saved.getId());
        }
        return saved;
    }

    /** Edits a campaign that has not been sent yet (draft/queued/scheduled). */
    public NotificationCampaign update(Long id, String title, String body, String destinationUrl,
                                       String icon, String tag, Instant scheduledAt, String actor) {
        NotificationCampaign campaign = requireEditable(id);
        campaign.setTitle(truncate(title, 200));
        campaign.setBody(truncate(body == null ? "" : body, 1000));
        campaign.setDestinationUrl(truncate(destinationUrl, 500));
        campaign.setIcon(truncate(icon, 500));
        campaign.setTag(truncate(tag, 64));
        // Moving a scheduled campaign back to "no time" turns it into a plain draft.
        if (scheduledAt != null) {
            campaign.setStatus(Status.SCHEDULED);
            campaign.setScheduledAt(scheduledAt);
        } else {
            campaign.setStatus(Status.DRAFT);
            campaign.setScheduledAt(null);
        }
        NotificationCampaign saved = campaigns.save(campaign);
        auditService.record(actor, "CAMPAIGN_UPDATED", "campaign:" + saved.getId(), saved.getTitle());
        return saved;
    }

    /** Sends a draft/scheduled/queued campaign right now. */
    public Status queueNow(Long id, String actor) {
        NotificationCampaign campaign = campaigns.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("No such campaign (#" + id + ")."));
        if (!EDITABLE.contains(campaign.getStatus())) {
            throw new IllegalArgumentException(
                    "This campaign has already been dispatched (status " + campaign.getStatus() + ").");
        }
        requirePushConfigured();
        campaign.setStatus(Status.QUEUED);
        campaign.setScheduledAt(null);
        campaigns.save(campaign);
        auditService.record(actor, "CAMPAIGN_SEND_NOW", "campaign:" + id, campaign.getTitle());
        startSend(id);
        return Status.QUEUED;
    }

    /**
     * Stops a campaign: queued/scheduled become cancelled immediately; a sending
     * campaign gets the cancel flag and stops before its next batch.
     */
    public Status requestCancel(Long id, String actor) {
        NotificationCampaign campaign = campaigns.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("No such campaign (#" + id + ")."));
        Status status = campaign.getStatus();
        if (status == Status.QUEUED || status == Status.SCHEDULED || status == Status.DRAFT) {
            campaign.setStatus(Status.CANCELLED);
            campaigns.save(campaign);
            auditService.record(actor, "CAMPAIGN_CANCELLED", "campaign:" + id, campaign.getTitle());
            return Status.CANCELLED;
        }
        if (status == Status.SENDING) {
            campaign.setCancelRequested(true);
            campaigns.save(campaign);
            auditService.record(actor, "CAMPAIGN_CANCELLING", "campaign:" + id, campaign.getTitle());
            return Status.SENDING;
        }
        return status;
    }

    /** Deletes a campaign and its delivery rows (never while it is sending). */
    public void delete(Long id, String actor) {
        NotificationCampaign campaign = campaigns.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("No such campaign (#" + id + ")."));
        if (campaign.getStatus() == Status.SENDING) {
            throw new IllegalArgumentException(
                    "Cancel the campaign first - it is still sending.");
        }
        deliveries.findByCampaignIdOrderByIdDesc(id, PageRequest.of(0, Integer.MAX_VALUE))
                .forEach(deliveries::delete);
        campaigns.delete(campaign);
        auditService.record(actor, "CAMPAIGN_DELETED", "campaign:" + id, campaign.getTitle());
    }

    // ------------------------------------------------------------- send engine

    /** Queues an asynchronous send; safe to call repeatedly (in-flight guard). */
    public void startSend(Long id) {
        if (!webPushService.isSendingEnabled()) {
            log.warn("Campaign {} not sent: web push is not configured (VAPID keys missing).", id);
            return;
        }
        if (!inFlight.add(id)) return;   // already sending in this JVM
        sendExecutor.submit(() -> {
            try {
                deliverCampaign(id);
            } catch (RuntimeException ex) {
                log.error("Campaign {} send job failed: {}", id, ex.getMessage());
                inFlight.remove(id);
            }
        });
    }

    /** True while this JVM is delivering the campaign (shown in the UI). */
    public boolean isSending(Long id) {
        return inFlight.contains(id);
    }

    /**
     * The delivery loop: batch over every active device, record one row per
     * device, then summarize into a terminal status. Runs on the send executor.
     */
    private void deliverCampaign(Long id) {
        NotificationCampaign campaign = campaigns.findById(id).orElse(null);
        if (campaign == null
                || (campaign.getStatus() != Status.QUEUED && campaign.getStatus() != Status.SCHEDULED)) {
            return;   // deleted, cancelled or already dispatched elsewhere
        }

        final String payload;
        try {
            payload = webPushService.buildCampaignPayload(id, campaign.getTitle(),
                    campaign.getBody(), campaign.getDestinationUrl(),
                    campaign.getIcon(), campaign.getTag());
        } catch (Exception ex) {
            log.error("Campaign {} payload could not be built: {}", id, ex.getMessage());
            finish(id, Status.FAILED, 0, 0);
            return;
        }

        transactions.executeWithoutResult(tx -> {
            NotificationCampaign running = campaigns.findById(id).orElseThrow();
            running.setStatus(Status.SENDING);
            running.setCancelRequested(false);
            campaigns.save(running);
        });

        int accepted = 0;
        int failed = 0;
        List<PushSubscription> batch = new ArrayList<>(BATCH_SIZE);
        for (PushSubscription device : subscriptions.findByActiveTrueOrderByIdAsc()) {
            batch.add(device);
            if (batch.size() < BATCH_SIZE) continue;

            int[] outcome = deliverBatch(id, payload, batch);
            accepted += outcome[0];
            failed += outcome[1];
            batch.clear();

            if (isCancelRequested(id)) {
                finish(id, Status.CANCELLED, accepted, failed);
                return;
            }
        }
        if (!batch.isEmpty()) {
            int[] outcome = deliverBatch(id, payload, batch);
            accepted += outcome[0];
            failed += outcome[1];
        }

        Status finalStatus;
        if (isCancelRequested(id)) finalStatus = Status.CANCELLED;
        else if (failed == 0) finalStatus = Status.SENT;              // zero devices is still a completed send
        else if (accepted == 0) finalStatus = Status.FAILED;
        else finalStatus = Status.PARTIALLY_FAILED;
        finish(id, finalStatus, accepted, failed);
    }

    /** Delivers one batch; returns {accepted, failed}. */
    private int[] deliverBatch(Long campaignId, String payload, List<PushSubscription> batch) {
        int accepted = 0;
        int failed = 0;
        List<PushSubscription> mutated = new ArrayList<>(batch.size());
        for (PushSubscription device : batch) {
            WebPushService.SendOutcome outcome = webPushService.sendOne(device, payload);
            mutated.add(device);
            CampaignDelivery.Result result = outcome.result == WebPushService.SendOutcome.Result.ACCEPTED
                    ? CampaignDelivery.Result.ACCEPTED : CampaignDelivery.Result.FAILED;
            recordDelivery(campaignId, device.getId(), result, outcome.attempts,
                    outcome.httpStatus, outcome.error);
            if (result == CampaignDelivery.Result.ACCEPTED) accepted++;
            else failed++;
        }
        subscriptions.saveAll(mutated);   // device state: success reset, failure bump, deactivation
        return new int[]{accepted, failed};
    }

    /** One row per (campaign, subscription): insert or update in place. */
    private void recordDelivery(Long campaignId, Long subscriptionId, CampaignDelivery.Result result,
                                int attempts, Integer httpStatus, String error) {
        transactions.executeWithoutResult(tx -> {
            CampaignDelivery delivery = deliveries
                    .findByCampaignIdAndSubscriptionId(campaignId, subscriptionId)
                    .orElseGet(() -> new CampaignDelivery(campaignId, subscriptionId, result));
            delivery.setResult(result);
            delivery.setAttemptCount(Math.max(1, attempts));
            delivery.setAttemptAt(Instant.now());
            delivery.setHttpStatus(httpStatus);
            delivery.setErrorMessage(truncate(error, 300));
            deliveries.save(delivery);
        });
    }

    private void finish(Long id, Status status, int accepted, int failed) {
        transactions.executeWithoutResult(tx -> {
            NotificationCampaign campaign = campaigns.findById(id).orElse(null);
            if (campaign == null) return;
            campaign.setStatus(status);
            campaign.setCancelRequested(false);
            campaigns.save(campaign);
            auditService.record("system", "CAMPAIGN_" + status.name(), "campaign:" + id,
                    "accepted=" + accepted + ", failed=" + failed);
        });
        inFlight.remove(id);
        log.info("Campaign {} finished as {} (accepted={}, failed={})", id, status, accepted, failed);
    }

    private boolean isCancelRequested(Long id) {
        return Boolean.TRUE.equals(transactions.execute(
                tx -> campaigns.findById(id).map(NotificationCampaign::isCancelRequested).orElse(true)));
    }

    /**
     * Persistent scheduler: fires scheduled campaigns the moment they come due.
     * Because the schedule lives in MySQL, anything due while the server was
     * down is picked up on the first tick after startup.
     */
    @Scheduled(fixedDelay = 15_000, initialDelay = 15_000)
    public void pollDueScheduled() {
        try {
            for (NotificationCampaign due
                    : campaigns.findByStatusAndScheduledAtBefore(Status.SCHEDULED, Instant.now())) {
                log.info("Scheduled campaign {} is due - starting delivery.", due.getId());
                startSend(due.getId());
            }
        } catch (RuntimeException ex) {
            log.warn("Campaign scheduler tick failed: {}", ex.getMessage());
        }
    }

    // ------------------------------------------------------------------ reads

    /** Paged campaign history with delivery counts for list + live progress. */
    public Map<String, Object> list(int page, int size, String search) {
        PageRequest pageable = PageRequest.of(Math.max(0, page), clampSize(size));
        String query = search == null ? "" : search.trim();
        Page<NotificationCampaign> result = query.isEmpty()
                ? campaigns.findAllBy(pageable)
                : campaigns.findByTitleContainingIgnoreCaseOrBodyContainingIgnoreCase(query, query, pageable);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", result.getContent().stream().map(this::describe).toList());
        out.put("page", result.getNumber());
        out.put("pageSize", result.getSize());
        out.put("total", result.getTotalElements());
        out.put("totalPages", result.getTotalPages());
        return out;
    }

    /** One campaign with its live delivery counts (the progress poll). */
    public Map<String, Object> get(Long id) {
        NotificationCampaign campaign = campaigns.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("No such campaign (#" + id + ")."));
        Map<String, Object> out = describe(campaign);
        out.put("sendingHere", isSending(id));
        return out;
    }

    /** Paginated per-device delivery attempts for one campaign (masked endpoints). */
    public Map<String, Object> results(Long id, int page, int size, String result) {
        if (!campaigns.existsById(id)) {
            throw new IllegalArgumentException("No such campaign (#" + id + ").");
        }
        PageRequest pageable = PageRequest.of(Math.max(0, page), clampSize(size));
        String filter = result == null ? "all" : result.trim().toLowerCase(Locale.ROOT);
        Page<CampaignDelivery> pageResult;
        if ("accepted".equals(filter) || "failed".equals(filter) || "skipped".equals(filter)) {
            pageResult = deliveries.findByCampaignIdAndResultOrderByIdDesc(id,
                    CampaignDelivery.Result.valueOf(filter.toUpperCase(Locale.ROOT)), pageable);
        } else {
            pageResult = deliveries.findByCampaignIdOrderByIdDesc(id, pageable);
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (CampaignDelivery delivery : pageResult.getContent()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", delivery.getId());
            item.put("subscriptionId", delivery.getSubscriptionId());
            PushSubscription device = delivery.getSubscriptionId() == null ? null
                    : subscriptions.findById(delivery.getSubscriptionId()).orElse(null);
            item.put("endpoint", device == null ? "(removed subscription)" : mask(device.getEndpoint()));
            item.put("student", device == null ? null : studentLabel(device.getStudentId()));
            item.put("deviceInfo", device == null ? null : device.getDeviceInfo());
            item.put("subscriptionActive", device == null ? null : device.isActive());
            item.put("result", delivery.getResult().name());
            item.put("attemptCount", delivery.getAttemptCount());
            item.put("attemptAt", delivery.getAttemptAt() == null ? null : delivery.getAttemptAt().toString());
            item.put("httpStatus", delivery.getHttpStatus());
            item.put("errorMessage", delivery.getErrorMessage());
            items.add(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("page", pageResult.getNumber());
        out.put("pageSize", pageResult.getSize());
        out.put("total", pageResult.getTotalElements());
        out.put("totalPages", pageResult.getTotalPages());
        return out;
    }

    /** Summary counters for the Notifications screen header. */
    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("subscribersActive", subscriptions.countByActiveTrue());
        out.put("subscribersTotal", subscriptions.count());
        out.put("campaignsTotal", campaigns.count());
        out.put("campaignsDispatched", campaigns.countByStatusIn(
                List.of(Status.SENT, Status.PARTIALLY_FAILED, Status.FAILED)));
        out.put("pushEnabled", webPushService.isSendingEnabled());
        return out;
    }

    // --------------------------------------------------------------- subscribers

    /** Paged, searchable device list with masked endpoints (never raw). */
    public Map<String, Object> listSubscribers(int page, int size, String search, String status) {
        PageRequest pageable = PageRequest.of(Math.max(0, page), clampSize(size));
        String query = search == null ? "" : search.trim();
        boolean activeOnly = "active".equalsIgnoreCase(status);
        boolean inactiveOnly = "inactive".equalsIgnoreCase(status);

        Page<PushSubscription> result;
        if (query.isEmpty()) {
            if (activeOnly) result = subscriptions.findByActiveOrderByIdDesc(true, pageable);
            else if (inactiveOnly) result = subscriptions.findByActiveOrderByIdDesc(false, pageable);
            else result = subscriptions.findAllByOrderByIdDesc(pageable);
        } else {
            List<Long> studentIds = students
                    .findByRollNoContainingIgnoreCaseOrNameContainingIgnoreCase(query, query)
                    .stream().map(Student::getId).toList();
            if (studentIds.isEmpty()) {
                result = Page.empty(pageable);
            } else if (activeOnly) {
                result = subscriptions.findByStudentIdInAndActiveOrderByIdDesc(studentIds, true, pageable);
            } else if (inactiveOnly) {
                result = subscriptions.findByStudentIdInAndActiveOrderByIdDesc(studentIds, false, pageable);
            } else {
                result = subscriptions.findByStudentIdInOrderByIdDesc(studentIds, pageable);
            }
        }

        List<Map<String, Object>> items = new ArrayList<>();
        for (PushSubscription device : result.getContent()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", device.getId());
            item.put("studentId", device.getStudentId());
            item.put("student", studentLabel(device.getStudentId()));
            item.put("endpoint", mask(device.getEndpoint()));
            item.put("deviceInfo", device.getDeviceInfo());
            item.put("active", device.isActive());
            item.put("failureCount", device.getFailureCount());
            item.put("lastError", device.getLastError());
            item.put("lastDeliveryAt", device.getLastDeliveryAt() == null
                    ? null : device.getLastDeliveryAt().toString());
            item.put("createdAt", device.getCreatedAt() == null
                    ? null : device.getCreatedAt().toString());
            items.add(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("page", result.getNumber());
        out.put("pageSize", result.getSize());
        out.put("total", result.getTotalElements());
        out.put("totalPages", result.getTotalPages());
        return out;
    }

    /** Stops targeting one device without deleting its history. */
    public void setSubscriberActive(Long id, boolean active, String actor) {
        PushSubscription device = subscriptions.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("No such device (#" + id + ")."));
        device.setActive(active);
        if (active) {
            device.setFailureCount(0);
            device.setLastError(null);
        }
        subscriptions.save(device);
        auditService.record(actor, active ? "SUBSCRIBER_REACTIVATED" : "SUBSCRIBER_DEACTIVATED",
                "subscription:" + id, studentLabel(device.getStudentId()));
    }

    /** Hard-deletes inactive subscriptions past the retention window. */
    public int cleanupInactive(String actor) {
        Instant cutoff = Instant.now().minus(Duration.ofDays(INACTIVE_RETENTION_DAYS));
        List<PushSubscription> stale = subscriptions.findAll().stream()
                .filter(d -> !d.isActive())
                .filter(d -> d.getCreatedAt() != null && d.getCreatedAt().isBefore(cutoff))
                .toList();
        subscriptions.deleteAll(stale);
        auditService.record(actor, "SUBSCRIBER_CLEANUP", "subscriptions",
                "deleted=" + stale.size() + " (inactive > " + INACTIVE_RETENTION_DAYS + " days)");
        return stale.size();
    }

    // ---------------------------------------------------------------- helpers

    private NotificationCampaign requireEditable(Long id) {
        NotificationCampaign campaign = campaigns.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("No such campaign (#" + id + ")."));
        if (!EDITABLE.contains(campaign.getStatus())) {
            throw new IllegalArgumentException(
                    "This campaign has already been dispatched (status " + campaign.getStatus()
                            + ") and can no longer be edited.");
        }
        return campaign;
    }

    private void requirePushConfigured() {
        if (!webPushService.isSendingEnabled()) {
            throw new IllegalArgumentException(
                    "Web push is not configured on this server (VAPID keys missing) - "
                            + "campaigns cannot be sent. Set VAPID_PUBLIC_KEY / VAPID_PRIVATE_KEY.");
        }
    }

    private Map<String, Object> describe(NotificationCampaign campaign) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", campaign.getId());
        out.put("title", campaign.getTitle());
        out.put("body", campaign.getBody());
        out.put("destinationUrl", campaign.getDestinationUrl());
        out.put("icon", campaign.getIcon());
        out.put("tag", campaign.getTag());
        out.put("status", campaign.getStatus().name());
        out.put("scheduledAt", campaign.getScheduledAt() == null
                ? null : campaign.getScheduledAt().toString());
        out.put("cancelRequested", campaign.isCancelRequested());
        out.put("createdBy", campaign.getCreatedBy());
        out.put("createdAt", campaign.getCreatedAt() == null
                ? null : campaign.getCreatedAt().toString());
        out.put("accepted", deliveries.countByCampaignIdAndResult(
                campaign.getId(), CampaignDelivery.Result.ACCEPTED));
        out.put("failed", deliveries.countByCampaignIdAndResult(
                campaign.getId(), CampaignDelivery.Result.FAILED));
        out.put("skipped", deliveries.countByCampaignIdAndResult(
                campaign.getId(), CampaignDelivery.Result.SKIPPED));
        return out;
    }

    /** "101 - Priya" style label; falls back to the raw student id. */
    private String studentLabel(Long studentId) {
        if (studentId == null) return null;
        return students.findById(studentId)
                .map(s -> (s.getRollNo() == null ? "" : s.getRollNo())
                        + (s.getName() == null ? "" : " - " + s.getName()))
                .orElse("student #" + studentId);
    }

    /** host/…last4 - raw endpoints are never sent to the browser. */
    static String mask(String endpoint) {
        if (endpoint == null) return "…";
        try {
            java.net.URI uri = java.net.URI.create(endpoint);
            String path = uri.getPath() == null ? "" : uri.getPath();
            String tail = path.length() > 4 ? path.substring(path.length() - 4) : path;
            return (uri.getHost() == null ? "…" : uri.getHost()) + "/…" + tail;
        } catch (RuntimeException ex) {
            return "…";
        }
    }

    private static int clampSize(int size) {
        return Math.min(Math.max(1, size), 200);
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() > max ? value.substring(0, max) : value;
    }
}
