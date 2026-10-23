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
 * One browser device registered for native web push ({@code push_subscriptions} table).
 *
 * <p>A subscription is bound to the student who registered it (the server always
 * uses the signed-in student; the client never supplies a student id). It holds
 * the standard W3C Push API subscription fields: the {@code endpoint} of the
 * browser's push service plus the {@code p256dh} and {@code auth} key material
 * used to encrypt payloads (RFC 8291). When the push service reports the
 * subscription as dead (HTTP 404/410) it is deactivated ({@code active = false})
 * instead of deleted, so delivery history survives.
 */
@Entity
@Table(name = "push_subscriptions", indexes = {
        @Index(name = "idx_push_subs_student", columnList = "student_id")
})
public class PushSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "student_id", nullable = false)
    private Long studentId;

    /** Push-service endpoint URL; unique across all students. */
    @Column(name = "endpoint", nullable = false, unique = true, length = 2000)
    private String endpoint;

    /** base64url P-256 public key of the browser subscription (payload encryption). */
    @Column(name = "p256dh", nullable = false, length = 128)
    private String p256dh;

    /** base64url 16-byte auth secret of the browser subscription. */
    @Column(name = "auth", nullable = false, length = 64)
    private String auth;

    /** Truncated user agent, purely informational. */
    @Column(name = "device_info", length = 250)
    private String deviceInfo;

    @Column(nullable = false)
    private boolean active = true;

    /** Consecutive failed delivery attempts for this subscription. */
    @Column(name = "failure_count", nullable = false)
    private int failureCount;

    /** Most recent delivery error, truncated. */
    @Column(name = "last_error", length = 300)
    private String lastError;

    private Instant lastDeliveryAt;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    public PushSubscription() {}

    public PushSubscription(Long studentId, String endpoint, String p256dh, String auth,
                            String deviceInfo) {
        this.studentId = studentId;
        this.endpoint = endpoint;
        this.p256dh = p256dh;
        this.auth = auth;
        this.deviceInfo = deviceInfo;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getStudentId() { return studentId; }
    public void setStudentId(Long studentId) { this.studentId = studentId; }

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

    public String getP256dh() { return p256dh; }
    public void setP256dh(String p256dh) { this.p256dh = p256dh; }

    public String getAuth() { return auth; }
    public void setAuth(String auth) { this.auth = auth; }

    public String getDeviceInfo() { return deviceInfo; }
    public void setDeviceInfo(String deviceInfo) { this.deviceInfo = deviceInfo; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public int getFailureCount() { return failureCount; }
    public void setFailureCount(int failureCount) { this.failureCount = failureCount; }

    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }

    public Instant getLastDeliveryAt() { return lastDeliveryAt; }
    public void setLastDeliveryAt(Instant lastDeliveryAt) { this.lastDeliveryAt = lastDeliveryAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
