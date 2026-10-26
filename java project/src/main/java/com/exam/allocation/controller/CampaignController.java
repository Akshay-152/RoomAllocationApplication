package com.exam.allocation.controller;

import com.exam.allocation.service.CampaignService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Administrator endpoints for the merged-in notification platform
 * (ROLE_ADMIN via SecurityConfig's {@code /api/admin/**} rule).
 *
 * <p>Covers the broadcast-campaign half of the standalone notification
 * tooling: compose (draft / schedule / send now), cancel, edit, delete,
 * live progress, per-device delivery results and subscriber management.
 *
 * <p>Validation happens here before anything reaches the campaign engine:
 * lengths, URL shapes and the schedule-in-the-future rule, so bad input can
 * never become a half-sent campaign.
 */
@RestController
@RequestMapping("/api/admin")
public class CampaignController {

    @Autowired private CampaignService campaignService;

    // ---------------------------------------------------------------- campaigns

    /** Paged campaign history with delivery counts (?page=&search=). */
    @GetMapping("/campaigns")
    public Map<String, Object> list(@RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "20") int size,
                                    @RequestParam(required = false) String search) {
        return campaignService.list(page, size, search);
    }

    /** One campaign with live counts - the progress endpoint the UI polls. */
    @GetMapping("/campaigns/{id}")
    public Map<String, Object> get(@PathVariable Long id) {
        return campaignService.get(id);
    }

    /**
     * Creates a campaign. Body: {@code {title, body, destinationUrl?, icon?,
     * tag?, scheduledAt?, draft?}}. Draft saves without sending; a future
     * scheduledAt registers the persistent scheduler; otherwise it sends now.
     */
    @PostMapping("/campaigns")
    public Map<String, Object> create(@RequestBody(required = false) Map<String, Object> body) {
        CampaignInput input = CampaignInput.from(body);
        return Map.of("campaign", campaignService.create(input.title, input.body,
                input.destinationUrl, input.icon, input.tag, input.scheduledAt,
                input.draft, actor()));
    }

    /** Edits a campaign that has not been dispatched yet. */
    @PutMapping("/campaigns/{id}")
    public Map<String, Object> update(@PathVariable Long id,
                                      @RequestBody(required = false) Map<String, Object> body) {
        CampaignInput input = CampaignInput.from(body);
        return Map.of("campaign", campaignService.update(id, input.title, input.body,
                input.destinationUrl, input.icon, input.tag, input.scheduledAt, actor()));
    }

    /** Sends a draft/scheduled campaign immediately. */
    @PostMapping("/campaigns/{id}/send")
    public Map<String, Object> sendNow(@PathVariable Long id) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", campaignService.queueNow(id, actor()).name());
        out.put("campaign", campaignService.get(id));
        return out;
    }

    /** Cancels a queued/scheduled campaign, or requests stop while sending. */
    @PostMapping("/campaigns/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable Long id) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", campaignService.requestCancel(id, actor()).name());
        out.put("campaign", campaignService.get(id));
        return out;
    }

    @DeleteMapping("/campaigns/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        campaignService.delete(id, actor());
        return Map.of("deleted", true);
    }

    /** Paginated per-device delivery attempts (?page=&result=all|accepted|failed). */
    @GetMapping("/campaigns/{id}/results")
    public Map<String, Object> results(@PathVariable Long id,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "20") int size,
                                       @RequestParam(required = false) String result) {
        return campaignService.results(id, page, size, result);
    }

    // --------------------------------------------------------------- subscribers

    /** Summary counters for the Notifications screen header. */
    @GetMapping("/push-stats")
    public Map<String, Object> stats() {
        return campaignService.stats();
    }

    /** Paged device list (?page=&search=&status=all|active|inactive); endpoints masked. */
    @GetMapping("/push-subscribers")
    public Map<String, Object> subscribers(@RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "20") int size,
                                           @RequestParam(required = false) String search,
                                           @RequestParam(defaultValue = "all") String status) {
        return campaignService.listSubscribers(page, size, search, status);
    }

    /** Stops targeting one device (history is kept). */
    @PostMapping("/push-subscribers/{id}/deactivate")
    public Map<String, Object> deactivate(@PathVariable Long id) {
        campaignService.setSubscriberActive(id, false, actor());
        return Map.of("id", id, "active", false);
    }

    /** Re-enables a previously deactivated device. */
    @PostMapping("/push-subscribers/{id}/reactivate")
    public Map<String, Object> reactivate(@PathVariable Long id) {
        campaignService.setSubscriberActive(id, true, actor());
        return Map.of("id", id, "active", true);
    }

    /** Hard-deletes inactive subscriptions older than the retention window. */
    @PostMapping("/push-subscribers/cleanup")
    public Map<String, Object> cleanup() {
        int deleted = campaignService.cleanupInactive(actor());
        return Map.of("deleted", deleted);
    }

    // ------------------------------------------------------------------ helpers

    private String actor() {
        return SecurityContextHolder.getContext().getAuthentication() == null
                ? "system" : SecurityContextHolder.getContext().getAuthentication().getName();
    }

    /** Validated, length-clamped compose input from the request body. */
    static final class CampaignInput {
        final String title;
        final String body;
        final String destinationUrl;
        final String icon;
        final String tag;
        final Instant scheduledAt;
        final boolean draft;

        private CampaignInput(String title, String body, String destinationUrl, String icon,
                              String tag, Instant scheduledAt, boolean draft) {
            this.title = title;
            this.body = body;
            this.destinationUrl = destinationUrl;
            this.icon = icon;
            this.tag = tag;
            this.scheduledAt = scheduledAt;
            this.draft = draft;
        }

        static CampaignInput from(Map<String, Object> body) {
            Map<String, Object> in = body == null ? Map.of() : body;
            String title = str(in.get("title"));
            if (title == null || title.isBlank()) {
                throw new IllegalArgumentException("A campaign needs a title.");
            }
            String bodyText = str(in.get("body"));
            if (bodyText != null && bodyText.length() > 1000) {
                throw new IllegalArgumentException("The message is limited to 1000 characters.");
            }
            String destinationUrl = str(in.get("destinationUrl"));
            if (destinationUrl != null && !destinationUrl.isBlank()
                    && !destinationUrl.startsWith("/") && !isHttpUrl(destinationUrl)) {
                throw new IllegalArgumentException(
                        "The destination must be an in-app path (e.g. /index.html#overview) "
                                + "or an http(s) URL.");
            }
            String icon = str(in.get("icon"));
            if (icon != null && !icon.isBlank() && !icon.startsWith("/") && !isHttpUrl(icon)) {
                throw new IllegalArgumentException(
                        "The icon must be a path (e.g. /icons/icon-192.png) or an http(s) URL.");
            }
            String tag = str(in.get("tag"));
            if (tag != null && tag.length() > 64) {
                throw new IllegalArgumentException("The tag is limited to 64 characters.");
            }
            Instant scheduledAt = parseInstant(str(in.get("scheduledAt")));
            if (scheduledAt != null && !scheduledAt.isAfter(Instant.now())) {
                throw new IllegalArgumentException("The scheduled time must be in the future.");
            }
            return new CampaignInput(title, bodyText == null ? "" : bodyText.trim(),
                    destinationUrl, icon, tag, scheduledAt, Boolean.TRUE.equals(in.get("draft")));
        }

        /** Accepts ISO-8601 instants and plain local date-times ("2026-11-02T09:30"). */
        private static Instant parseInstant(String raw) {
            if (raw == null || raw.isBlank()) return null;
            String value = raw.trim();
            try {
                return Instant.parse(value);
            } catch (DateTimeParseException ignored) {
                // fall through to local date-time parsing
            }
            try {
                return LocalDateTime.parse(value).atZone(ZoneId.systemDefault()).toInstant();
            } catch (DateTimeParseException ex) {
                throw new IllegalArgumentException(
                        "The scheduled time must look like 2026-11-02T09:30 (local time).");
            }
        }

        private static boolean isHttpUrl(String value) {
            String lower = value.toLowerCase(Locale.ROOT);
            return (lower.startsWith("https://") || lower.startsWith("http://"))
                    && value.length() <= 500;
        }

        private static String str(Object value) {
            return value instanceof String s ? s.trim() : null;
        }
    }
}
