package com.exam.allocation.controller;

import com.exam.allocation.model.NotificationCampaign;
import com.exam.allocation.model.NotificationCampaign.Status;
import com.exam.allocation.service.AuditService;
import com.exam.allocation.service.CampaignService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Compose-input validation happens before anything reaches the engine, so a
 * bad title, URL or schedule can never become a half-sent campaign.
 */
@ExtendWith(MockitoExtension.class)
class CampaignControllerTest {

    @Mock private CampaignService campaignService;
    @Mock private AuditService auditService;

    @InjectMocks private CampaignController controller;

    @Test
    void aBlankTitleIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> CampaignController.CampaignInput.from(
                Map.of("title", "   ", "body", "x")));
    }

    @Test
    void destinationMustBeAnInAppPathOrHttpUrl() {
        assertThrows(IllegalArgumentException.class, () -> CampaignController.CampaignInput.from(
                Map.of("title", "t", "destinationUrl", "javascript:alert(1)")));
        CampaignController.CampaignInput ok = CampaignController.CampaignInput.from(
                Map.of("title", "t", "destinationUrl", "/index.html#seat"));
        assertEquals("/index.html#seat", ok.destinationUrl);
    }

    @Test
    void aScheduleInThePastIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> CampaignController.CampaignInput.from(
                Map.of("title", "t", "scheduledAt", Instant.now().minus(1, ChronoUnit.HOURS).toString())));
    }

    @Test
    void aLocalDateTimeScheduleIsParsedToAnInstant() {
        CampaignController.CampaignInput input = CampaignController.CampaignInput.from(
                Map.of("title", "t", "scheduledAt", "2099-01-01T09:30"));
        assertTrue(input.scheduledAt.isAfter(Instant.now()));
    }

    @Test
    void createForwardsValidatedInputAndTheDraftFlag() {
        when(campaignService.create(anyString(), anyString(), any(), any(), any(), any(), anyBoolean(), anyString()))
                .thenReturn(notificationCampaign());

        controller.create(Map.of("title", "Hall tickets", "body", "Collect from the office",
                "draft", true));

        verify(campaignService).create(eq("Hall tickets"), eq("Collect from the office"),
                isNull(), isNull(), isNull(), isNull(), eq(true), anyString());
    }

    private NotificationCampaign notificationCampaign() {
        NotificationCampaign c = new NotificationCampaign();
        c.setId(1L);
        c.setTitle("Hall tickets");
        c.setStatus(Status.DRAFT);
        return c;
    }
}
