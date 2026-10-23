package com.exam.allocation.service;

import com.exam.allocation.model.Notification;
import com.exam.allocation.model.Notification.Type;
import com.exam.allocation.model.PushSubscription;
import com.exam.allocation.model.Student;
import com.exam.allocation.repository.NotificationRepository;
import com.exam.allocation.repository.PushSubscriptionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Notification fan-out rules:
 * - one notification per student per dedupe key (retried publications are safe),
 * - a push failure never removes the stored in-app notification,
 * - read/unread operations only ever touch the caller's own rows.
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock private NotificationRepository notificationRepository;
    @Mock private PushSubscriptionRepository pushSubscriptionRepository;
    @Mock private WebPushService webPushService;
    @Mock private NotificationStreamService notificationStreamService;

    @InjectMocks private NotificationService notificationService;

    private Student student(long id) {
        Student s = new Student();
        s.setId(id);
        s.setRollNo("R" + id);
        return s;
    }

    private PushSubscription subscription(long studentId, String endpoint) {
        return new PushSubscription(studentId, endpoint, "p256dh-key", "auth-key", null);
    }

    // ------------------------------------------------------------------ fan-out

    @Test
    void aDuplicateDedupeKeyIsSkippedNotResent() {
        Student a = student(1L);
        when(notificationRepository.existsByStudentIdAndDedupeKey(1L, "exam:5:published:x"))
                .thenReturn(true);
        when(notificationRepository.existsByStudentIdAndDedupeKey(2L, "exam:5:published:x"))
                .thenReturn(false);
        when(notificationRepository.save(any(Notification.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        NotificationService.FanOutResult result = notificationService.notify(
                List.of(a, student(2L)), Type.EXAM_PUBLISHED, "Published", "Body", "exams", 5L,
                "exam:5:published:x");

        assertEquals(1, result.getCreated());
        assertEquals(1, result.getDuplicatesSkipped());
        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(saved.capture());
        assertEquals(2L, saved.getValue().getStudentId().longValue());
    }

    @Test
    void freshNotificationsArePublishedToTheLiveStream() {
        Student a = student(1L);
        when(notificationRepository.existsByStudentIdAndDedupeKey(1L, "live-key")).thenReturn(false);
        when(notificationRepository.save(any(Notification.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        notificationService.notify(List.of(a), Type.EXAM_PUBLISHED,
                "Published", "Body", "exams", 5L, "live-key");

        ArgumentCaptor<Notification> notification = ArgumentCaptor.forClass(Notification.class);
        verify(notificationStreamService).publish(notification.capture());
        assertEquals(1L, notification.getValue().getStudentId());
        assertEquals(Type.EXAM_PUBLISHED, notification.getValue().getType());
    }

    @Test
    void pushFailureKeepsTheStoredNotification() {
        Student a = student(1L);
        when(notificationRepository.existsByStudentIdAndDedupeKey(1L, "k")).thenReturn(false);
        when(notificationRepository.save(any(Notification.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(pushSubscriptionRepository.findByStudentIdAndActiveTrue(1L))
                .thenReturn(List.of(subscription(1L, "https://push.example/sub-1")));
        // The push service explodes for this student.
        when(webPushService.send(anyList(), any(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("Push service down"));

        NotificationService.FanOutResult result = notificationService.notify(
                List.of(a), Type.ANNOUNCEMENT, "T", "B", "overview", null, "k");

        // The notification itself was created and stays; only the push is lost.
        assertEquals(1, result.getCreated());
        assertEquals(1, result.getPushFailed());
        assertEquals(0, result.getPushed());
        verify(notificationRepository, never()).delete(any(Notification.class));
    }

    @Test
    void deadTokensArePersistedAsInactive() {
        when(notificationRepository.existsByStudentIdAndDedupeKey(1L, "k")).thenReturn(false);
        when(notificationRepository.save(any(Notification.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        PushSubscription dead = subscription(1L, "https://push.example/dead");
        dead.setActive(false);
        when(pushSubscriptionRepository.findByStudentIdAndActiveTrue(1L)).thenReturn(List.of(dead));
        WebPushService.DeliveryResult delivery = new WebPushService.DeliveryResult();
        delivery.invalidTokens.add(dead);
        when(webPushService.send(anyList(), any(), anyString(), anyString(), anyString()))
                .thenReturn(delivery);

        notificationService.notify(List.of(student(1L)), Type.SEAT_PUBLISHED, "T", "B", "seat", 1L, "k");

        // The deactivated token is written back so it is never targeted again.
        verify(pushSubscriptionRepository).saveAll(List.of(dead));
    }

    @Test
    void anEmptyAudienceCreatesNothing() {
        NotificationService.FanOutResult result = notificationService.notify(
                List.of(), Type.EXAM_PUBLISHED, "T", "B", "exams", 1L, "k");
        assertEquals(0, result.getCreated());
        verify(notificationRepository, never()).save(any());
    }

    // ------------------------------------------------------------------ student side

    @Test
    void markingAnotherStudentsNotificationReadFails() {
        // The repository scope (studentId + id) is the ownership check.
        when(notificationRepository.findByIdAndStudentId(9L, 1L)).thenReturn(Optional.empty());

        assertFalse(notificationService.markRead(1L, 9L));
    }

    @Test
    void markingAnAlreadyReadNotificationIsIdempotent() {
        Notification notification = new Notification(1L, Type.ANNOUNCEMENT, "T", "B", "overview", null, "k");
        notification.setReadAt(java.time.Instant.now());
        when(notificationRepository.findByIdAndStudentId(5L, 1L)).thenReturn(Optional.of(notification));

        assertTrue(notificationService.markRead(1L, 5L));
        verify(notificationRepository, never()).save(any(Notification.class));
    }

    @Test
    void inboxUnreadOnlyHidesReadEntries() {
        Notification unread = new Notification(1L, Type.EXAM_PUBLISHED, "U", null, "exams", 1L, "a");
        Notification read = new Notification(1L, Type.ANNOUNCEMENT, "R", null, "overview", null, "b");
        read.setReadAt(java.time.Instant.now());
        when(notificationRepository.findByStudentIdOrderByCreatedAtDesc(1L))
                .thenReturn(new ArrayList<>(List.of(unread, read)));

        assertEquals(1, notificationService.inbox(1L, true).size());
        assertEquals("U", notificationService.inbox(1L, true).get(0).getTitle());
        assertEquals(2, notificationService.inbox(1L, false).size());
    }

    @Test
    void unreadCountCountsOnlyUnreadRows() {
        when(notificationRepository.countByStudentIdAndReadAtIsNull(1L)).thenReturn(3L);
        assertEquals(3L, notificationService.unreadCount(1L));
    }

    @Test
    void studentDeletionRemovesNotificationsAndDevices() {
        notificationService.deleteForStudent(1L);
        verify(notificationRepository).deleteByStudentId(1L);
        verify(pushSubscriptionRepository).deleteByStudentId(1L);
    }

    @Test
    void titlesAndBodiesAreTruncatedToColumnLimits() {
        when(notificationRepository.existsByStudentIdAndDedupeKey(eq(1L), anyString())).thenReturn(false);
        when(notificationRepository.save(any(Notification.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        notificationService.notify(List.of(student(1L)), Type.ANNOUNCEMENT,
                "x".repeat(500), "y".repeat(5000), "overview", null, "k");

        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(saved.capture());
        assertEquals(200, saved.getValue().getTitle().length());
        assertEquals(1000, saved.getValue().getBody().length());
        assertNull(saved.getValue().getExamId());
    }
}
