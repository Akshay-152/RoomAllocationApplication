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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Campaign engine rules:
 * - drafts and schedules never touch the push transport,
 * - a queued campaign delivers to every active device and records one row per device,
 * - dispatched campaigns can no longer be edited or re-sent,
 * - cancellation flips queued campaigns immediately and flags sending ones,
 * - endpoints are always masked for the admin UI and cleanup only removes
 *   stale inactive subscriptions.
 */
@ExtendWith(MockitoExtension.class)
class CampaignServiceTest {

    @Mock private NotificationCampaignRepository campaigns;
    @Mock private CampaignDeliveryRepository deliveries;
    @Mock private PushSubscriptionRepository subscriptions;
    @Mock private StudentRepository students;
    @Mock private WebPushService webPushService;
    @Mock private AuditService auditService;
    @Mock private TransactionTemplate transactions;

    @InjectMocks private CampaignService campaignService;

    /* Transactions run inline so the engine's steps are observable in tests. */
    private void transactionsRunInline() {
        doAnswer(inv -> {
            Consumer<Object> action = inv.getArgument(0);
            action.accept(null);
            return null;
        }).when(transactions).executeWithoutResult(any());
        when(transactions.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> callback = inv.getArgument(0);
            return callback.doInTransaction(null);
        });
    }

    private NotificationCampaign campaign(long id, Status status) {
        NotificationCampaign c = new NotificationCampaign();
        c.setId(id);
        c.setTitle("Title " + id);
        c.setBody("Body");
        c.setStatus(status);
        return c;
    }

    private PushSubscription device(long id, long studentId, String endpoint) {
        PushSubscription d = new PushSubscription(studentId, endpoint, "p256dh", "auth", "Chrome");
        d.setId(id);
        return d;
    }

    private WebPushService.SendOutcome accepted() {
        return new WebPushService.SendOutcome(WebPushService.SendOutcome.Result.ACCEPTED, 201, null, 1);
    }

    private WebPushService.SendOutcome gone() {
        return new WebPushService.SendOutcome(WebPushService.SendOutcome.Result.GONE, 410,
                "subscription is gone", 1);
    }

    // ------------------------------------------------------------------ compose

    @Test
    void aDraftIsSavedWithoutTouchingThePushTransport() {
        when(campaigns.save(any(NotificationCampaign.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        NotificationCampaign saved = campaignService.create("Draft title", "draft body",
                "/index.html#overview", null, null, null, true, "admin");

        assertEquals(Status.DRAFT, saved.getStatus());
        assertEquals("Draft title", saved.getTitle());
        verify(webPushService, never()).isSendingEnabled();
        verify(auditService).record(eq("admin"), eq("CAMPAIGN_DRAFTED"), anyString(), anyString());
    }

    @Test
    void aScheduledCampaignStoresItsFireTime() {
        when(campaigns.save(any(NotificationCampaign.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        Instant fireAt = Instant.now().plus(Duration.ofHours(2));

        NotificationCampaign saved = campaignService.create("Later", "body",
                null, null, null, fireAt, false, "admin");

        assertEquals(Status.SCHEDULED, saved.getStatus());
        assertEquals(fireAt, saved.getScheduledAt());
    }

    @Test
    void sendingIsRefusedWhilePushIsNotConfigured() {
        when(campaigns.findById(9L)).thenReturn(Optional.of(campaign(9L, Status.QUEUED)));
        when(webPushService.isSendingEnabled()).thenReturn(false);

        assertThrows(IllegalArgumentException.class, () -> campaignService.queueNow(9L, "admin"));
    }

    @Test
    void aDispatchedCampaignCanBeNeitherEditedNorResent() {
        when(campaigns.findById(3L)).thenReturn(Optional.of(campaign(3L, Status.SENT)));

        assertThrows(IllegalArgumentException.class, () -> campaignService.update(
                3L, "t", "b", null, null, null, null, "admin"));
        assertThrows(IllegalArgumentException.class, () -> campaignService.queueNow(3L, "admin"));
    }

    // ------------------------------------------------------------- cancellation

    @Test
    void cancellingAQueuedCampaignStopsItImmediately() {
        NotificationCampaign queued = campaign(4L, Status.QUEUED);
        when(campaigns.findById(4L)).thenReturn(Optional.of(queued));
        when(campaigns.save(any(NotificationCampaign.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        Status status = campaignService.requestCancel(4L, "admin");

        assertEquals(Status.CANCELLED, status);
        assertEquals(Status.CANCELLED, queued.getStatus());
    }

    @Test
    void cancellingASendingCampaignRaisesTheStopFlag() {
        NotificationCampaign sending = campaign(5L, Status.SENDING);
        when(campaigns.findById(5L)).thenReturn(Optional.of(sending));
        when(campaigns.save(any(NotificationCampaign.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        Status status = campaignService.requestCancel(5L, "admin");

        assertEquals(Status.SENDING, status);
        assertTrue(sending.isCancelRequested());
    }

    // ------------------------------------------------------------------ engine

    @Test
    void aQueuedCampaignDeliversToEveryActiveDeviceAndRecordsEachOutcome() throws Exception {
        transactionsRunInline();
        NotificationCampaign queued = campaign(7L, Status.QUEUED);
        when(campaigns.findById(7L)).thenReturn(Optional.of(queued));
        when(campaigns.save(any(NotificationCampaign.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(webPushService.isSendingEnabled()).thenReturn(true);
        when(webPushService.buildCampaignPayload(any(), anyString(), anyString(), any(), any(), any()))
                .thenReturn("{\"v\":1}");
        PushSubscription good = device(11L, 101L, "https://push.example/aaaa");
        PushSubscription dead = device(12L, 102L, "https://push.example/bbbb");
        when(subscriptions.findByActiveTrueOrderByIdAsc()).thenReturn(List.of(good, dead));
        when(webPushService.sendOne(eq(good), anyString())).thenReturn(accepted());
        when(webPushService.sendOne(eq(dead), anyString())).thenReturn(gone());
        when(deliveries.findByCampaignIdAndSubscriptionId(any(), any())).thenReturn(Optional.empty());
        when(deliveries.save(any(CampaignDelivery.class))).thenAnswer(inv -> inv.getArgument(0));
        when(subscriptions.saveAll(any(List.class))).thenAnswer(inv -> inv.getArgument(0));

        campaignService.startSend(7L);

        // The engine runs on its own thread; wait (bounded) for the terminal status.
        long deadline = System.currentTimeMillis() + 3000;
        while (queued.getStatus() != Status.PARTIALLY_FAILED && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(Status.PARTIALLY_FAILED, queued.getStatus());
        assertFalse(queued.isCancelRequested());

        ArgumentCaptor<CampaignDelivery> row = ArgumentCaptor.forClass(CampaignDelivery.class);
        verify(deliveries, org.mockito.Mockito.times(2)).save(row.capture());
        List<CampaignDelivery> rows = row.getAllValues();
        assertEquals(CampaignDelivery.Result.ACCEPTED, rows.get(0).getResult());
        assertEquals(11L, rows.get(0).getSubscriptionId());
        assertEquals(CampaignDelivery.Result.FAILED, rows.get(1).getResult());
        assertEquals(12L, rows.get(1).getSubscriptionId());
        // Both device rows are persisted back (success reset, failure bump, deactivation).
        verify(subscriptions).saveAll(List.of(good, dead));
        assertFalse(campaignService.isSending(7L));
    }

    // --------------------------------------------------------------- subscribers

    @Test
    void subscriberListingsMaskEndpointsAndCarryTheStudentLabel() {
        PushSubscription device = device(21L, 101L, "https://fcm.googleapis.com/fcm/send/xyz12345");
        when(students.findByRollNoContainingIgnoreCaseOrNameContainingIgnoreCase("pri", "pri"))
                .thenReturn(List.of(student(101L, "101", "Priya")));
        when(students.findById(101L)).thenReturn(Optional.of(student(101L, "101", "Priya")));
        when(subscriptions.findByStudentIdInOrderByIdDesc(eq(List.of(101L)), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(device), PageRequest.of(0, 10), 1));

        var page = campaignService.listSubscribers(0, 10, "pri", "all");

        @SuppressWarnings("unchecked")
        var items = (List<java.util.Map<String, Object>>) page.get("items");
        assertEquals(1, items.size());
        assertEquals("fcm.googleapis.com/…2345", items.get(0).get("endpoint"));
        assertEquals("101 - Priya", items.get(0).get("student"));
    }

    @Test
    void cleanupOnlyRemovesInactiveSubscriptionsPastTheRetentionWindow() {
        PushSubscription staleInactive = device(31L, 1L, "https://push.example/old");
        staleInactive.setActive(false);
        staleInactive.setCreatedAt(Instant.now().minus(Duration.ofDays(60)));
        PushSubscription recentInactive = device(32L, 1L, "https://push.example/new");
        recentInactive.setActive(false);
        recentInactive.setCreatedAt(Instant.now().minus(Duration.ofDays(2)));
        PushSubscription live = device(33L, 1L, "https://push.example/live");
        when(subscriptions.findAll()).thenReturn(List.of(staleInactive, recentInactive, live));

        int deleted = campaignService.cleanupInactive("admin");

        assertEquals(1, deleted);
        verify(subscriptions).deleteAll(List.of(staleInactive));
        verify(auditService).record(eq("admin"), eq("SUBSCRIBER_CLEANUP"), anyString(), anyString());
    }

    @Test
    void deactivatingADeviceKeepsItButStopsTargeting() {
        PushSubscription device = device(41L, 1L, "https://push.example/x");
        when(subscriptions.findById(41L)).thenReturn(Optional.of(device));
        when(subscriptions.save(any(PushSubscription.class))).thenAnswer(inv -> inv.getArgument(0));

        campaignService.setSubscriberActive(41L, false, "admin");

        assertFalse(device.isActive());
        verify(auditService).record(eq("admin"), eq("SUBSCRIBER_DEACTIVATED"), anyString(), anyString());
    }

    private Student student(long id, String rollNo, String name) {
        Student s = new Student();
        s.setId(id);
        s.setRollNo(rollNo);
        s.setName(name);
        return s;
    }
}
