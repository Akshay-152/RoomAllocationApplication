package com.exam.allocation.service;

import com.exam.allocation.model.Notification.Type;
import com.exam.allocation.model.NotificationTemplate;
import com.exam.allocation.repository.NotificationTemplateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Admin-editable push templates:
 * - built-in defaults reproduce the original wording exactly,
 * - stored rows override the defaults per type,
 * - placeholder substitution never destroys unknown tokens,
 * - validation and reset behave like the Settings screen expects.
 */
@ExtendWith(MockitoExtension.class)
class NotificationTemplateServiceTest {

    @Mock private NotificationTemplateRepository templateRepository;
    @InjectMocks private NotificationTemplateService templateService;

    @Test
    void defaultWordingsRenderWhenNoCustomTemplateIsStored() {
        when(templateRepository.findByType("EXAM_PUBLISHED")).thenReturn(Optional.empty());

        NotificationTemplateService.Message message = templateService.render(
                Type.EXAM_PUBLISHED,
                Map.of("subject", "\"Maths\"", "when", "on 2026-05-04 at 10:00"));

        assertEquals("Exam published: \"Maths\"", message.title());
        assertEquals("Your examination \"Maths\" is scheduled on 2026-05-04 at 10:00.",
                message.body());
    }

    @Test
    void storedCustomWordingsReplaceTheDefaults() {
        when(templateRepository.findByType("SEAT_PUBLISHED")).thenReturn(Optional.of(
                new NotificationTemplate("SEAT_PUBLISHED", "Seat news: {subject}", "Sit at {seat}")));

        NotificationTemplateService.Message message = templateService.render(
                Type.SEAT_PUBLISHED,
                Map.of("subject", "\"Maths\"", "seat", "Room 101, seat R2-C03"));

        assertEquals("Seat news: \"Maths\"", message.title());
        assertEquals("Sit at Room 101, seat R2-C03", message.body());
    }

    @Test
    void announcementTemplatesDefaultToTheAdminsOwnText() {
        when(templateRepository.findByType("ANNOUNCEMENT")).thenReturn(Optional.empty());

        NotificationTemplateService.Message message = templateService.render(
                Type.ANNOUNCEMENT, Map.of("title", "Admit card released", "body", "Download it."));

        assertEquals("Admit card released", message.title());
        assertEquals("Download it.", message.body());
    }

    @Test
    void unknownPlaceholdersAreLeftUntouched() {
        // A typo in a template shows the literal token instead of eating text.
        assertEquals("keep {mystery}", NotificationTemplateService.substitute(
                "keep {mystery}", Map.of("title", "x")));
        Map<String, String> withNullValue = new java.util.HashMap<>();
        withNullValue.put("title", "x");
        withNullValue.put("body", null);
        assertEquals("title is x", NotificationTemplateService.substitute(
                "title is {title}", withNullValue));
        assertEquals("body was ", NotificationTemplateService.substitute(
                "body was {body}", withNullValue));
    }

    @Test
    void savingRejectsBlankOrOversizedPatterns() {
        assertThrows(IllegalArgumentException.class,
                () -> templateService.save(Type.EXAM_PUBLISHED, "   ", "body"));
        assertThrows(IllegalArgumentException.class,
                () -> templateService.save(Type.EXAM_PUBLISHED, "x".repeat(201), "body"));
        assertThrows(IllegalArgumentException.class,
                () -> templateService.save(Type.EXAM_PUBLISHED, "ok", "y".repeat(1001)));
        assertThrows(IllegalArgumentException.class,
                () -> templateService.save(null, "title", "body"));
        verify(templateRepository, never()).save(any(NotificationTemplate.class));
    }

    @Test
    void saveUpsertsByTypeAndResetDeletesTheRow() {
        when(templateRepository.findByType("EXAM_CANCELLED")).thenReturn(Optional.empty());
        when(templateRepository.save(any(NotificationTemplate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        templateService.save(Type.EXAM_CANCELLED, "Gone: {subject}", "Body");

        ArgumentCaptor<NotificationTemplate> saved =
                ArgumentCaptor.forClass(NotificationTemplate.class);
        verify(templateRepository).save(saved.capture());
        assertEquals("EXAM_CANCELLED", saved.getValue().getType());
        assertEquals("Gone: {subject}", saved.getValue().getTitle());

        templateService.reset(Type.EXAM_CANCELLED);
        verify(templateRepository).deleteByType("EXAM_CANCELLED");
    }

    @Test
    void listShowsCurrentTextAndMarksCustomRows() {
        when(templateRepository.findAll()).thenReturn(List.of(
                new NotificationTemplate("SEAT_PUBLISHED", "My seat title", "My seat body")));

        List<Map<String, Object>> list = templateService.list();

        assertEquals(Type.values().length, list.size());
        Map<String, Object> seat = list.stream()
                .filter(e -> "SEAT_PUBLISHED".equals(e.get("key"))).findFirst().orElseThrow();
        assertEquals(Boolean.FALSE, seat.get("isDefault"));
        assertEquals("My seat title", seat.get("title"));
        Map<String, Object> exam = list.stream()
                .filter(e -> "EXAM_PUBLISHED".equals(e.get("key"))).findFirst().orElseThrow();
        assertEquals(Boolean.TRUE, exam.get("isDefault"));
        assertTrue(((List<?>) exam.get("placeholders")).contains("subject"));
        assertFalse(list.isEmpty());
    }
}
