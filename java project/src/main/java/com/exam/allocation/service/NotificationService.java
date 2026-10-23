package com.exam.allocation.service;

import com.exam.allocation.model.Notification;
import com.exam.allocation.model.Notification.Type;
import com.exam.allocation.model.PushSubscription;
import com.exam.allocation.model.Student;
import com.exam.allocation.repository.NotificationRepository;
import com.exam.allocation.repository.PushSubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Creates in-app notifications for students and forwards them to web push.
 *
 * <p>Order of operations is deliberate:
 * <ol>
 *   <li><b>Persist first</b> - the notification row is saved (idempotently,
 *       via the per-student dedupe key), so a retried publication never
 *       duplicates it and a push-service outage never loses it.</li>
 *   <li><b>Push second, best-effort</b> - every push failure is recorded on the
 *       device row and swallowed; it never rolls back the business operation
 *       or removes the stored notification.</li>
 * </ol>
 *
 * <p>All entry points are reached from admin flows (publish, cancel,
 * announcement, room edit), which SecurityConfig already restricts to
 * ROLE_ADMIN; the student-side methods are only ever called with the id of the
 * signed-in student.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notificationRepository;
    private final PushSubscriptionRepository pushSubscriptionRepository;
    private final WebPushService webPushService;
    private final NotificationStreamService notificationStreamService;

    public NotificationService(NotificationRepository notificationRepository,
                               PushSubscriptionRepository pushSubscriptionRepository,
                               WebPushService webPushService,
                               NotificationStreamService notificationStreamService) {
        this.notificationRepository = notificationRepository;
        this.pushSubscriptionRepository = pushSubscriptionRepository;
        this.webPushService = webPushService;
        this.notificationStreamService = notificationStreamService;
    }

    /** Outcome of one fan-out, shown to the admin who triggered the event. */
    public static class FanOutResult {
        private int created;
        private int duplicatesSkipped;
        private int pushed;
        private int pushFailed;
        private boolean pushEnabled;

        public int getCreated() { return created; }
        public int getDuplicatesSkipped() { return duplicatesSkipped; }
        public int getPushed() { return pushed; }
        public int getPushFailed() { return pushFailed; }
        public boolean isPushEnabled() { return pushEnabled; }

        /** Folds another result into this one (used when fanning out per student). */
        public void add(FanOutResult other) {
            if (other == null) return;
            created += other.created;
            duplicatesSkipped += other.duplicatesSkipped;
            pushed += other.pushed;
            pushFailed += other.pushFailed;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("created", created);
            out.put("duplicatesSkipped", duplicatesSkipped);
            out.put("pushed", pushed);
            out.put("pushFailed", pushFailed);
            out.put("pushEnabled", pushEnabled);
            return out;
        }
    }

    /**
     * Notifies the given students about one event. Each student receives at
     * most one notification per {@code dedupeKey}, so re-running a publication
     * action is safe and creates no duplicates.
     *
     * @param recipients target students (already filtered by the caller's audience rules)
     * @param link       in-app section: "exams", "seat", "overview" or "profile"
     */
    @Transactional
    public FanOutResult notify(List<Student> recipients, Type type, String title, String body,
                               String link, Long examId, String dedupeKey) {
        FanOutResult outcome = new FanOutResult();
        outcome.pushEnabled = webPushService.isSendingEnabled();
        if (recipients == null || recipients.isEmpty()) return outcome;

        List<Notification> fresh = new ArrayList<>();
        for (Student student : recipients) {
            if (student == null || student.getId() == null) continue;
            if (notificationRepository.existsByStudentIdAndDedupeKey(student.getId(), dedupeKey)) {
                outcome.duplicatesSkipped++;
                continue;
            }
            fresh.add(notificationRepository.save(
                    new Notification(student.getId(), type, truncate(title, 200),
                            truncate(body, 1000), link, examId, dedupeKey)));
            outcome.created++;
        }
        pushFresh(fresh, outcome);
        return outcome;
    }

    /** Single-recipient convenience used by room-change events. */
    @Transactional
    public FanOutResult notify(Student student, Type type, String title, String body,
                               String link, Long examId, String dedupeKey) {
        return notify(student == null ? List.of() : List.of(student),
                type, title, body, link, examId, dedupeKey);
    }

    /** Push is best-effort: failures must not roll back the stored notifications. */
    private void pushFresh(List<Notification> fresh, FanOutResult outcome) {
        for (Notification notification : fresh) {
            notificationStreamService.publish(notification);
            try {
                List<PushSubscription> devices =
                        pushSubscriptionRepository.findByStudentIdAndActiveTrue(notification.getStudentId());
                if (devices.isEmpty()) continue;
                WebPushService.DeliveryResult delivery = webPushService.send(
                        devices, notification.getId(), notification.getTitle(),
                        notification.getBody(), notification.getLink());
                pushSubscriptionRepository.saveAll(delivery.delivered);
                pushSubscriptionRepository.saveAll(delivery.invalidTokens);
                pushSubscriptionRepository.saveAll(delivery.failed);
                outcome.pushed += delivery.delivered.size();
                outcome.pushFailed += delivery.failed.size() + delivery.invalidTokens.size();
            } catch (RuntimeException ex) {
                // The in-app notification is already saved; push stays best-effort.
                log.warn("Push delivery for notification {} failed: {}",
                        notification.getId(), ex.getMessage());
                outcome.pushFailed++;
            }
        }
    }

    // ---------------------------------------------------------------- student side

    /** The signed-in student's inbox, newest first. */
    @Transactional(readOnly = true)
    public List<Notification> inbox(Long studentId, boolean unreadOnly) {
        List<Notification> all = notificationRepository.findByStudentIdOrderByCreatedAtDesc(studentId);
        if (!unreadOnly) return all;
        return all.stream().filter(n -> n.getReadAt() == null).toList();
    }

    @Transactional(readOnly = true)
    public long unreadCount(Long studentId) {
        return notificationRepository.countByStudentIdAndReadAtIsNull(studentId);
    }

    /** Marks one notification read; only ever touches the caller's own row. */
    @Transactional
    public boolean markRead(Long studentId, Long notificationId) {
        return notificationRepository.findByIdAndStudentId(notificationId, studentId)
                .map(n -> {
                    if (n.getReadAt() == null) {
                        n.setReadAt(Instant.now());
                        notificationRepository.save(n);
                    }
                    return true;
                })
                .orElse(false);
    }

    @Transactional
    public int markAllRead(Long studentId) {
        return notificationRepository.markAllRead(studentId, Instant.now());
    }

    /** Called when a student row is deleted, so no orphaned rows remain. */
    @Transactional
    public void deleteForStudent(Long studentId) {
        notificationRepository.deleteByStudentId(studentId);
        pushSubscriptionRepository.deleteByStudentId(studentId);
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() > max ? value.substring(0, max) : value;
    }
}
