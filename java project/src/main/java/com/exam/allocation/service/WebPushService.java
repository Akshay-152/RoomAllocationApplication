package com.exam.allocation.service;

import com.exam.allocation.config.PushProperties;
import com.exam.allocation.model.PushSubscription;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import nl.martijndwars.webpush.Urgency;
import org.apache.http.HttpResponse;
import org.jose4j.lang.JoseException;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.Security;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Sends native Web Push messages (RFC 8291 encryption + RFC 8292 VAPID) for
 * persisted notifications - the same self-hosted protocol the notification
 * tooling uses, with no Firebase or third-party service involved.
 *
 * <p><b>Graceful degradation:</b> when VAPID keys are missing/invalid the push
 * service is never constructed and every send is a no-op result - in-app
 * notifications still work and the application never fails because of push.
 *
 * <p><b>Failure classification</b> mirrors the reference implementation:
 * <ul>
 *   <li>{@code 404/410} - the subscription no longer exists: deactivate it and
 *       never target it again;</li>
 *   <li>transient statuses ({@code 408, 425, 429, 5xx} and network errors) -
 *       bounded retry with exponential backoff, then a retryable failure;</li>
 *   <li>anything else - a permanent failure recorded on the device row.</li>
 * </ul>
 * Dead/failed devices are reported back through {@link DeliveryResult} so the
 * caller can persist their state; the stored in-app notification is never
 * rolled back by a push failure.
 */
@Service
public class WebPushService {

    private static final Logger log = LoggerFactory.getLogger(WebPushService.class);

    /** Keep undelivered messages at the push service for max 7 days. */
    private static final int TTL_SECONDS = 7 * 24 * 3600;
    private static final int MAX_RETRIES = 2;
    private static final long RETRY_BASE_MS = 250L;

    /** HTTP statuses that are worth retrying (rate limits + server errors). */
    private static final Set<Integer> TRANSIENT_STATUSES =
            Set.of(408, 425, 429, 500, 502, 503, 504, 522, 524);

    private static final ObjectMapper JSON = new ObjectMapper();

    private final PushProperties properties;
    private volatile PushService pushService;   // null while not configured

    public WebPushService(PushProperties properties) {
        this.properties = properties;
        initialise();
    }

    /** Builds the signer once at startup; failures only disable push. */
    private void initialise() {
        if (!properties.isConfigured()) {
            log.info("VAPID keys not configured - web push sending is disabled; "
                    + "in-app notifications continue to work.");
            return;
        }
        try {
            if (Security.getProvider("BC") == null) {
                Security.addProvider(new BouncyCastleProvider());
            }
            pushService = new PushService(properties.getVapidPublicKey(),
                    properties.getVapidPrivateKey(), properties.getVapidSubject());
            log.info("Web push sending enabled (self-hosted VAPID, subject {}).",
                    properties.getVapidSubject());
        } catch (GeneralSecurityException | RuntimeException ex) {
            pushService = null;
            log.warn("VAPID keys could not be used: {}. Web push sending stays disabled; "
                    + "in-app notifications still work.", ex.getMessage());
        }
    }

    public boolean isSendingEnabled() {
        return pushService != null;
    }

    /** Outcome of one multicast push attempt. */
    public static class DeliveryResult {
        public final List<PushSubscription> delivered = new ArrayList<>();
        public final List<PushSubscription> invalidTokens = new ArrayList<>();
        public final List<PushSubscription> failed = new ArrayList<>();
    }

    /**
     * Pushes one notification to the given devices. Each device keeps its own
     * outcome so valid subscriptions survive failures of other devices.
     *
     * @param link in-app section token; the payload opens the portal on it
     */
    public DeliveryResult send(List<PushSubscription> devices, Long notificationId,
                               String title, String body, String link) {
        DeliveryResult result = new DeliveryResult();
        if (devices == null || devices.isEmpty()) return result;
        if (pushService == null) {
            // Not configured: treat as transient failure so nothing is deactivated.
            result.failed.addAll(devices);
            return result;
        }

        final String payload;
        try {
            payload = buildPayload(notificationId, title, body, link);
        } catch (JsonProcessingException ex) {
            log.debug("Push payload for notification {} could not be serialised: {}",
                    notificationId, ex.getMessage());
            result.failed.addAll(devices);
            return result;
        }

        for (PushSubscription device : devices) {
            sendToOne(device, payload, result);
        }
        return result;
    }

    /** One device, with bounded retry + exponential backoff on transient failures. */
    private void sendToOne(PushSubscription device, String payload, DeliveryResult result) {
        Notification notification = null;
        try {
            notification = Notification.builder()
                    .endpoint(device.getEndpoint())
                    .userPublicKey(device.getP256dh())
                    .userAuth(device.getAuth())
                    .payload(payload)
                    .ttl(TTL_SECONDS)
                    .urgency(Urgency.NORMAL)
                    .topic(topicFor(device))
                    .build();
        } catch (RuntimeException | GeneralSecurityException ex) {
            // Stored keys that no longer parse are permanent: the browser must re-subscribe.
            markFailed(device, "bad subscription: " + ex.getMessage());
            result.failed.add(device);
            return;
        }

        int attempts = 0;
        while (true) {
            attempts++;
            try {
                HttpResponse response = pushService.send(notification);
                int status = response == null || response.getStatusLine() == null
                        ? 0 : response.getStatusLine().getStatusCode();
                if (status >= 200 && status < 300) {
                    device.setFailureCount(0);
                    device.setLastError(null);
                    device.setLastDeliveryAt(Instant.now());
                    result.delivered.add(device);
                    return;
                }
                if (status == 404 || status == 410) {
                    // The push service no longer knows this subscription.
                    device.setActive(false);
                    device.setLastError(truncate("HTTP " + status + ": subscription is gone"));
                    result.invalidTokens.add(device);
                    return;
                }
                if (TRANSIENT_STATUSES.contains(status) && attempts <= MAX_RETRIES) {
                    backoff(attempts);
                    continue;
                }
                markFailed(device, "HTTP " + status);
                result.failed.add(device);
                return;
            } catch (IOException | GeneralSecurityException | JoseException
                     | java.util.concurrent.ExecutionException ex) {
                // Network-level problem: worth one bounded retry, then record it.
                if (attempts <= MAX_RETRIES) {
                    backoff(attempts);
                    continue;
                }
                markFailed(device, ex.getMessage());
                result.failed.add(device);
                return;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                markFailed(device, "interrupted");
                result.failed.add(device);
                return;
            } catch (RuntimeException ex) {
                // Anything unexpected: count a failure, keep the subscription alive.
                log.debug("Push to device {} failed: {}", device.getId(), ex.getMessage());
                markFailed(device, ex.getMessage());
                result.failed.add(device);
                return;
            }
        }
    }

    /**
     * The compact JSON payload rendered by the service worker, same schema as
     * the reference notification tooling: {@code {v, id, title, body, url,
     * icon, badge, tag, timestamp}}.
     */
    private String buildPayload(Long notificationId, String title, String body, String link)
            throws JsonProcessingException {
        ObjectNode payload = JSON.createObjectNode();
        payload.put("v", 1);
        if (notificationId != null) payload.put("id", notificationId);
        payload.put("title", title == null ? "Notification" : title);
        payload.put("body", body == null ? "" : body);
        String section = link == null || link.isBlank() ? "overview" : link.trim();
        payload.put("url", "/index.html#" + section);
        payload.put("icon", "/icons/icon-192.png");
        payload.put("badge", "/icons/badge.png");
        payload.put("tag", notificationId == null ? "exam-notification" : "n-" + notificationId);
        payload.put("timestamp", Instant.now().toString());
        return JSON.writeValueAsString(payload);
    }

    /** Collapses re-sends of the same notification at the push service. */
    private static String topicFor(PushSubscription device) {
        if (device.getId() == null) return null;
        String topic = "n" + device.getId();
        return topic.length() > 32 ? topic.substring(0, 32) : topic;
    }

    private void markFailed(PushSubscription device, String error) {
        device.setFailureCount(device.getFailureCount() + 1);
        device.setLastError(truncate(error));
    }

    /** Bounded exponential backoff with jitter, mirroring the reference tooling. */
    private void backoff(int attempts) {
        long delay = RETRY_BASE_MS * (1L << (attempts - 1))
                + (long) (Math.random() * RETRY_BASE_MS);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static String truncate(String value) {
        if (value == null) return null;
        return value.length() > 300 ? value.substring(0, 300) : value;
    }
}
