package com.exam.allocation.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One in-app notification for one student ({@code notifications} table).
 *
 * <p>Records are persisted <b>before</b> any web-push attempt, so a push
 * failure can never lose the message: the student still sees it in the portal
 * inbox. Push delivery state lives on {@link PushSubscription}.
 *
 * <p>{@code examId} is a plain column (deliberately <b>not</b> a foreign key):
 * deleting an exam must not be blocked by, or cascade-delete, the students'
 * notification history.
 *
 * <p>{@code link} is an in-app section token ("exams", "seat", "overview") - the
 * server only ever writes values from that fixed set, so a notification can
 * never point the browser at an unsafe/external location.
 */
@Entity
@Table(name = "notifications", indexes = {
        @Index(name = "idx_notifications_student", columnList = "student_id"),
        @Index(name = "idx_notifications_dedupe", columnList = "student_id,dedupe_key")
})
public class Notification {

    /** The kind of event that produced this notification. */
    public enum Type {
        EXAM_PUBLISHED,
        EXAM_RESCHEDULED,
        EXAM_CANCELLED,
        SEAT_PUBLISHED,
        SEAT_CHANGED,
        ROOM_CHANGED,
        ANNOUNCEMENT
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Recipient. The student row owns the notification; deleted with the student. */
    @Column(name = "student_id", nullable = false)
    private Long studentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Type type;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 1000)
    private String body;

    /** In-app section to open on click: "exams", "seat" or "overview". */
    @Column(length = 32)
    private String link;

    /** Plain id of the related exam - no FK, so exam deletion stays possible. */
    @Column(name = "exam_id")
    private Long examId;

    /**
     * Idempotency key, unique per student (see repository query). A retried
     * publication with the same key is skipped instead of duplicated.
     */
    @Column(length = 120)
    private String dedupeKey;

    /** Null while unread; set when the student marks it read. */
    private Instant readAt;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    public Notification() {}

    public Notification(Long studentId, Type type, String title, String body,
                        String link, Long examId, String dedupeKey) {
        this.studentId = studentId;
        this.type = type;
        this.title = title;
        this.body = body;
        this.link = link;
        this.examId = examId;
        this.dedupeKey = dedupeKey;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getStudentId() { return studentId; }
    public void setStudentId(Long studentId) { this.studentId = studentId; }

    public Type getType() { return type; }
    public void setType(Type type) { this.type = type; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }

    public String getLink() { return link; }
    public void setLink(String link) { this.link = link; }

    public Long getExamId() { return examId; }
    public void setExamId(Long examId) { this.examId = examId; }

    public String getDedupeKey() { return dedupeKey; }
    public void setDedupeKey(String dedupeKey) { this.dedupeKey = dedupeKey; }

    public Instant getReadAt() { return readAt; }
    public void setReadAt(Instant readAt) { this.readAt = readAt; }

    public boolean isRead() { return readAt != null; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
