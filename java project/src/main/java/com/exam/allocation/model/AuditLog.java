package com.exam.allocation.model;

import jakarta.persistence.*;
import java.time.Instant;

/** An audit entry ({@code audit_logs} table) for sensitive administrative actions. */
@Entity
@Table(name = "audit_logs")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Who performed the action (username, or "system" for seeding). */
    @Column(nullable = false)
    private String actor;

    /** Short action name, e.g. "EXAM_PUBLISH", "STUDENT_RESET_PASSWORD". */
    @Column(nullable = false)
    private String action;

    /** What the action affected, e.g. "exam:12". */
    private String target;

    @Column(length = 1000)
    private String details;

    @Column(nullable = false)
    private Instant timestamp = Instant.now();

    public AuditLog() {}

    public AuditLog(String actor, String action, String target, String details) {
        this.actor = actor;
        this.action = action;
        this.target = target;
        this.details = details;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }

    public String getDetails() { return details; }
    public void setDetails(String details) { this.details = details; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
}
