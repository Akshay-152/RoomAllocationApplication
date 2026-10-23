package com.exam.allocation.service;

import com.exam.allocation.model.Notification.Type;
import com.exam.allocation.model.NotificationTemplate;
import com.exam.allocation.repository.NotificationTemplateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin-editable message templates for every notification type.
 *
 * <p>Each type ships a <b>default</b> title/body that reproduces the original
 * hard-coded wording exactly; a row in {@code notification_templates} exists
 * only once the administrator changed it, so "reset to default" is just a
 * delete. {@link #render(Type, Map)} merges the stored (or default) pattern
 * with the event's {@code {placeholder}} values - the result feeds both the
 * in-app bell notification and the browser push, so the two always agree.
 *
 * <p>Unknown placeholders are left untouched (never silently destroyed), so a
 * template edited with a typo degrades to showing the literal token rather
 * than eating part of the message.
 */
@Service
public class NotificationTemplateService {

    /** A rendered message: final title + body for one notification. */
    public record Message(String title, String body) {}

    /** Shipped default wording + metadata shown in Settings. */
    private record Template(String label, String title, String body, List<String> placeholders) {}

    private static final Map<Type, Template> DEFAULTS = new EnumMap<>(Type.class);

    static {
        DEFAULTS.put(Type.EXAM_PUBLISHED, new Template(
                "Exam published",
                "Exam published: {subject}",
                "Your examination {subject} is scheduled {when}.",
                List.of("subject", "when", "date", "time")));
        DEFAULTS.put(Type.EXAM_RESCHEDULED, new Template(
                "Exam rescheduled",
                "Exam rescheduled: {subject}",
                "Your examination {subject} has been rescheduled to {when}.",
                List.of("subject", "when", "date", "time")));
        DEFAULTS.put(Type.EXAM_CANCELLED, new Template(
                "Exam cancelled",
                "Exam cancelled: {subject}",
                "Your examination {subject} ({when}) has been cancelled.",
                List.of("subject", "when", "date", "time")));
        DEFAULTS.put(Type.SEAT_PUBLISHED, new Template(
                "Seat published",
                "Seat allocated: {subject}",
                "Your seat for {subject} is now published. {seat}.",
                List.of("subject", "seat", "when", "date")));
        DEFAULTS.put(Type.SEAT_CHANGED, new Template(
                "Seat changed",
                "Seat changed: {subject}",
                "Your seat for {subject} has changed. {seat}.",
                List.of("subject", "seat", "when", "date")));
        DEFAULTS.put(Type.ROOM_CHANGED, new Template(
                "Room updated",
                "Room updated: {room}",
                "Room details for {subject} changed: {room}. Check My Seat for your venue.",
                List.of("subject", "room")));
        DEFAULTS.put(Type.ANNOUNCEMENT, new Template(
                "Announcement",
                "{title}",
                "{body}",
                List.of("title", "body")));
    }

    private final NotificationTemplateRepository templateRepository;

    public NotificationTemplateService(NotificationTemplateRepository templateRepository) {
        this.templateRepository = templateRepository;
    }

    /**
     * The stored (or default) pattern for a type with event values substituted.
     *
     * @param vars placeholder values, e.g. {@code subject}, {@code when},
     *             {@code seat}, {@code room}, {@code title}, {@code body}
     */
    @Transactional(readOnly = true)
    public Message render(Type type, Map<String, String> vars) {
        Template fallback = DEFAULTS.get(type);
        if (fallback == null) {
            throw new IllegalArgumentException("Unknown notification type: " + type);
        }
        NotificationTemplate stored = templateRepository.findByType(type.name()).orElse(null);
        String titlePattern = stored != null && notBlank(stored.getTitle())
                ? stored.getTitle() : fallback.title();
        String bodyPattern = stored != null && stored.getBody() != null
                ? stored.getBody() : fallback.body();
        return new Message(substitute(titlePattern, vars), substitute(bodyPattern, vars));
    }

    /** All types with current text (stored or default) for the Settings screen. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        Map<String, NotificationTemplate> storedByType = new LinkedHashMap<>();
        for (NotificationTemplate row : templateRepository.findAll()) {
            storedByType.put(row.getType(), row);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Type type : Type.values()) {
            Template fallback = DEFAULTS.get(type);
            if (fallback == null) continue;
            NotificationTemplate stored = storedByType.get(type.name());
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("key", type.name());
            entry.put("label", fallback.label());
            entry.put("title", stored != null && notBlank(stored.getTitle())
                    ? stored.getTitle() : fallback.title());
            entry.put("body", stored != null && stored.getBody() != null
                    ? stored.getBody() : fallback.body());
            entry.put("placeholders", fallback.placeholders());
            entry.put("isDefault", stored == null);
            entry.put("updatedAt", stored == null ? null
                    : (stored.getUpdatedAt() == null ? null : stored.getUpdatedAt().toString()));
            out.add(entry);
        }
        return out;
    }

    /** Validates and upserts one template; blank titles are rejected. */
    @Transactional
    public void save(Type type, String title, String body) {
        if (!DEFAULTS.containsKey(type)) {
            throw new IllegalArgumentException("Unknown notification type: " + type);
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("A template needs a title pattern.");
        }
        String cleanTitle = title.trim();
        String cleanBody = body == null ? "" : body.trim();
        if (cleanTitle.length() > 200) {
            throw new IllegalArgumentException("The title pattern is limited to 200 characters.");
        }
        if (cleanBody.length() > 1000) {
            throw new IllegalArgumentException("The body pattern is limited to 1000 characters.");
        }
        NotificationTemplate row = templateRepository.findByType(type.name())
                .orElseGet(() -> new NotificationTemplate(type.name(), cleanTitle, cleanBody));
        row.setTitle(cleanTitle);
        row.setBody(cleanBody);
        row.setUpdatedAt(Instant.now());
        templateRepository.save(row);
    }

    /** Removes the stored customisation; the built-in default applies again. */
    @Transactional
    public void reset(Type type) {
        if (!DEFAULTS.containsKey(type)) {
            throw new IllegalArgumentException("Unknown notification type: " + type);
        }
        templateRepository.deleteByType(type.name());
    }

    /** Substitutes {@code {key}} tokens; unknown tokens stay as they are. */
    static String substitute(String pattern, Map<String, String> vars) {
        if (pattern == null || pattern.isEmpty() || vars == null || vars.isEmpty()) {
            return pattern;
        }
        String out = pattern;
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            if (entry.getKey() == null) continue;
            String value = entry.getValue() == null ? "" : entry.getValue();
            out = out.replace("{" + entry.getKey() + "}", value);
        }
        return out;
    }

    private static boolean notBlank(String value) { return value != null && !value.isBlank(); }
}
