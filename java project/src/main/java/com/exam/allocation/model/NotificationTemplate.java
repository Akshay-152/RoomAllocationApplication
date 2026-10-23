package com.exam.allocation.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One administrator-edited message template ({@code notification_templates}
 * table), keyed by {@link Notification.Type}. A row exists only once the admin
 * changed the default wording - a missing row means "use the built-in default",
 * so upgrading or resetting never loses the shipped text.
 *
 * <p>Templates contain {@code {placeholder}} tokens (for example
 * {@code {subject}}, {@code {seat}}, {@code {when}}) which the server
 * substitutes with real event values before persisting a notification; both
 * the in-app bell message and the browser push carry the rendered text.
 */
@Entity
@Table(name = "notification_templates", indexes = {
        @Index(name = "idx_notification_templates_type", columnList = "type")
})
public class NotificationTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Notification type name, e.g. EXAM_PUBLISHED (unique). */
    @Column(name = "type", nullable = false, unique = true, length = 40)
    private String type;

    /** Title pattern, max 200 characters (same limit as a notification row). */
    @Column(nullable = false, length = 200)
    private String title;

    /** Body pattern, max 1000 characters (same limit as a notification row). */
    @Column(nullable = false, length = 1000)
    private String body;

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    public NotificationTemplate() {}

    public NotificationTemplate(String type, String title, String body) {
        this.type = type;
        this.title = title;
        this.body = body;
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
