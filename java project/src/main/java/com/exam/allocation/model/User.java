package com.exam.allocation.model;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * A login account ({@code users} table).
 *
 * <p>Administrators are created from configuration; a student account is created
 * automatically when an administrator adds a student, using the student's roll
 * number as both username and initial password (see {@link UserAccountService}).
 */
@Entity
@Table(name = "users")
public class User {

    public enum Role { ADMIN, STUDENT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String username;

    /** BCrypt hash - never returned by the API. */
    @Column(nullable = false)
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role = Role.STUDENT;

    @Column(nullable = false)
    private boolean enabled = true;

    /** True while the user is still on the initial (roll number) password. */
    @Column(nullable = false)
    private boolean mustChangePassword = false;

    /** Student owning this account (null for administrators). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id")
    private Student student;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    public User() {}

    public User(String username, String password, Role role) {
        this.username = username;
        this.password = password;
        this.role = role;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public boolean isMustChangePassword() { return mustChangePassword; }
    public void setMustChangePassword(boolean mustChangePassword) { this.mustChangePassword = mustChangePassword; }

    public Student getStudent() { return student; }
    public void setStudent(Student student) { this.student = student; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
