package com.exam.allocation.service;

import com.exam.allocation.config.PushProperties;
import com.exam.allocation.model.PushSubscription;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Graceful degradation of native Web Push: missing/invalid VAPID keys only
 * disable sending - they never throw at startup, never deactivate devices
 * (a configured push service would retry them on the next event) and never
 * block the in-app notification flow.
 */
class WebPushServiceTest {

    @Test
    void staysDisabledWithoutVapidKeysAndNeverDeactivatesDevices() {
        PushProperties properties = new PushProperties();
        properties.setVapidPublicKey("");
        properties.setVapidPrivateKey("");
        WebPushService service = new WebPushService(properties);

        assertFalse(service.isSendingEnabled());

        PushSubscription device = new PushSubscription(
                1L, "https://push.example/sub", "p256dh", "auth", null);
        WebPushService.DeliveryResult result =
                service.send(List.of(device), 5L, "Title", "Body", "seat");

        assertEquals(0, result.delivered.size());
        assertEquals(1, result.failed.size());
        // An unconfigured server must not blame the device: no deactivation,
        // no failure count - the same subscription is retried on the next event.
        assertTrue(device.isActive());
        assertEquals(0, device.getFailureCount());
    }

    @Test
    void invalidKeysOnlyDisableSendingInsteadOfFailingStartup() {
        PushProperties properties = new PushProperties();
        properties.setVapidPublicKey("not-a-real-key");
        properties.setVapidPrivateKey("not-a-real-key-either");

        WebPushService service = new WebPushService(properties);

        assertFalse(service.isSendingEnabled());
    }

    @Test
    void anEmptyDeviceListIsANoOp() {
        WebPushService service = new WebPushService(new PushProperties());

        WebPushService.DeliveryResult result =
                service.send(List.of(), 1L, "T", "B", "exams");

        assertEquals(0, result.delivered.size());
        assertEquals(0, result.failed.size());
        assertEquals(0, result.invalidTokens.size());
    }
}
