package com.exam.allocation.controller;

import com.exam.allocation.config.PushProperties;
import com.exam.allocation.model.Notification;
import com.exam.allocation.model.PushSubscription;
import com.exam.allocation.model.Student;
import com.exam.allocation.repository.PushSubscriptionRepository;
import com.exam.allocation.service.NotificationService;
import com.exam.allocation.service.NotificationStreamService;
import com.exam.allocation.service.SessionService;
import com.exam.allocation.service.WebPushService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Student-scoped notification endpoints (ROLE_STUDENT via SecurityConfig).
 *
 * <p>Every handler resolves the student from the <b>signed-in session</b> -
 * the request body never carries a student id, so one student can neither read
 * nor register devices for another. Device registration additionally re-checks
 * endpoint ownership when a subscription is reused.
 *
 * <p>Push devices are standard W3C Push API subscriptions (native Web Push,
 * VAPID): the browser sends {@code {endpoint, keys:{p256dh, auth}}} after the
 * student explicitly granted notification permission.
 */
@RestController
@RequestMapping("/api/student")
public class NotificationController {

    @Autowired private NotificationService notificationService;
    @Autowired private PushSubscriptionRepository pushSubscriptionRepository;
    @Autowired private SessionService sessionService;
    @Autowired private PushProperties pushProperties;
    @Autowired private WebPushService webPushService;
    @Autowired private NotificationStreamService notificationStreamService;

    /** Inbox listing; ?unreadOnly=true limits it to unread entries. */
    @GetMapping("/notifications")
    public Map<String, Object> list(@RequestParam(required = false) Boolean unreadOnly) {
        Student student = requireStudent();
        boolean onlyUnread = Boolean.TRUE.equals(unreadOnly);
        List<Map<String, Object>> items = notificationService.inbox(student.getId(), onlyUnread)
                .stream().map(this::describe).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("notifications", items);
        out.put("unreadCount", notificationService.unreadCount(student.getId()));
        return out;
    }

    /** Live event stream for this signed-in student; session cookies authenticate it. */
    @GetMapping(value = "/notifications/stream", produces = "text/event-stream")
    public SseEmitter stream() {
        Student student = requireStudent();
        return notificationStreamService.subscribe(student.getId());
    }

    @GetMapping("/notifications/unread-count")
    public Map<String, Object> unreadCount() {
        Student student = requireStudent();
        return Map.of("unreadCount", notificationService.unreadCount(student.getId()));
    }

    /** Marks one of the caller's notifications read (404-style false for foreign ids). */
    @PostMapping("/notifications/{id}/read")
    public Map<String, Object> markRead(@PathVariable Long id) {
        Student student = requireStudent();
        boolean marked = notificationService.markRead(student.getId(), id);
        if (!marked) {
            throw new IllegalArgumentException("Notification not found.");
        }
        return Map.of("read", true, "unreadCount", notificationService.unreadCount(student.getId()));
    }

    @PostMapping("/notifications/read-all")
    public Map<String, Object> markAllRead() {
        Student student = requireStudent();
        int updated = notificationService.markAllRead(student.getId());
        return Map.of("marked", updated, "unreadCount", 0L);
    }

    // ------------------------------------------------------------- device management

    /** Push permission state for this browser + the registered devices. */
    @GetMapping("/push/status")
    public Map<String, Object> pushStatus() {
        Student student = requireStudent();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pushEnabled", webPushService.isSendingEnabled());
        // Public VAPID key for pushManager.subscribe(); public by design.
        out.put("vapidKey", pushProperties.getVapidPublicKey());
        out.put("devices", pushSubscriptionRepository.findByStudentIdAndActiveTrue(student.getId())
                .stream().map(this::describeDevice).toList());
        return out;
    }

    /**
     * Registers this browser's push subscription for the signed-in student.
     * Body: {@code {"subscription": {"endpoint": "...", "keys": {"p256dh": "...",
     * "auth": "..."}}, "deviceInfo": "optional label"}}.
     */
    @PostMapping("/push/register")
    public Map<String, Object> register(@RequestBody(required = false) Map<String, Object> body,
                                        HttpServletRequest request) {
        Student student = requireStudent();
        Map<String, Object> registration = body == null ? Map.of() : body;

        Map<String, Object> subscriptionMap = asMap(registration.get("subscription"));
        String rawEndpoint = stringValue(subscriptionMap.get("endpoint"));
        Map<String, Object> keys = asMap(subscriptionMap.get("keys"));
        String rawP256dh = stringValue(keys.get("p256dh"));
        String rawAuth = stringValue(keys.get("auth"));

        if (rawEndpoint == null || rawEndpoint.isBlank()) {
            throw new IllegalArgumentException("A push subscription endpoint is required.");
        }
        final String endpoint = rawEndpoint.trim();
        if (endpoint.length() > 1800 || !(endpoint.startsWith("https://") || endpoint.startsWith("http://"))) {
            throw new IllegalArgumentException("The push subscription endpoint is not a valid URL.");
        }
        if (rawP256dh == null || rawP256dh.isBlank() || rawAuth == null || rawAuth.isBlank()) {
            throw new IllegalArgumentException("The push subscription keys (p256dh, auth) are required.");
        }
        final String p256dh = rawP256dh.trim();
        final String auth = rawAuth.trim();
        if (p256dh.length() > 128 || auth.length() > 64) {
            throw new IllegalArgumentException("The push subscription keys are malformed.");
        }

        // An endpoint identifies one browser: move it to the current student rather
        // than letting a shared device keep receiving another account's pushes.
        PushSubscription subscription = pushSubscriptionRepository.findByEndpoint(endpoint)
                .orElseGet(() -> new PushSubscription(student.getId(), endpoint, p256dh, auth, null));
        subscription.setStudentId(student.getId());
        subscription.setEndpoint(endpoint);
        subscription.setP256dh(p256dh);
        subscription.setAuth(auth);
        subscription.setActive(true);
        subscription.setFailureCount(0);
        subscription.setLastError(null);
        String deviceInfo = stringValue(registration.get("deviceInfo"));
        if (deviceInfo != null && !deviceInfo.isBlank()) {
            subscription.setDeviceInfo(deviceInfo.length() > 250
                    ? deviceInfo.substring(0, 250) : deviceInfo.trim());
        } else if (subscription.getDeviceInfo() == null) {
            subscription.setDeviceInfo(truncate(request.getHeader("User-Agent"), 250));
        }
        PushSubscription saved = pushSubscriptionRepository.save(subscription);
        return Map.of("registered", true, "id", saved.getId(),
                "message", "This device will receive exam notifications.");
    }

    /** Unregisters one of the caller's devices by id, or an endpoint via ?endpoint=. */
    @DeleteMapping("/push/unregister")
    public Map<String, Object> unregister(@RequestParam(required = false) Long id,
                                          @RequestParam(required = false) String endpoint) {
        Student student = requireStudent();
        PushSubscription subscription = null;
        if (id != null) {
            subscription = pushSubscriptionRepository.findByIdAndStudentId(id, student.getId())
                    .orElse(null);
        } else if (endpoint != null && !endpoint.isBlank()) {
            subscription = pushSubscriptionRepository.findByEndpoint(endpoint.trim())
                    .filter(s -> student.getId().equals(s.getStudentId()))
                    .orElse(null);
        }
        if (subscription == null) {
            throw new IllegalArgumentException("No such registered device on your account.");
        }
        pushSubscriptionRepository.delete(subscription);
        return Map.of("unregistered", true);
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> describe(Notification n) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", n.getId());
        out.put("type", n.getType() == null ? null : n.getType().name());
        out.put("title", n.getTitle());
        out.put("body", n.getBody());
        out.put("link", n.getLink());
        out.put("examId", n.getExamId());
        out.put("read", n.isRead());
        out.put("readAt", n.getReadAt() == null ? null : n.getReadAt().toString());
        out.put("createdAt", n.getCreatedAt() == null ? null : n.getCreatedAt().toString());
        return out;
    }

    private Map<String, Object> describeDevice(PushSubscription s) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", s.getId());
        out.put("deviceInfo", s.getDeviceInfo());
        out.put("active", s.isActive());
        out.put("failureCount", s.getFailureCount());
        out.put("lastError", s.getLastError());
        out.put("createdAt", s.getCreatedAt() == null ? null : s.getCreatedAt().toString());
        return out;
    }

    private Student requireStudent() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return sessionService.requireStudent(username);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    private static String stringValue(Object value) {
        return value instanceof String ? (String) value : null;
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() > max ? value.substring(0, max) : value;
    }
}
