package br.com.roboparts.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users", schema = "roboparts")
public class EmployeeUser {
    @Id
    private UUID id;
    @Column(nullable = false, length = 100, updatable = false)
    private String name;
    @Column(nullable = false, length = 254, unique = true, updatable = false)
    private String email;
    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;
    @Column(name = "password_version", nullable = false)
    private long passwordVersion;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified;
    @Column(nullable = false)
    private boolean active = true;

    protected EmployeeUser() { }
    public EmployeeUser(UUID id, String name, String email, String passwordHash, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.email = email;
        this.passwordHash = passwordHash;
        this.createdAt = createdAt;
    }
    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public Instant getCreatedAt() { return createdAt; }
    public boolean isEmailVerified() { return emailVerified; }
    public void verifyEmail() { emailVerified = true; }
    public long getPasswordVersion() { return passwordVersion; }
    public void resetPassword(String hash) { passwordHash = hash; passwordVersion++; }
    public boolean isActive() { return active; }
    public void setActive(boolean value) { if (active != value) { active = value; passwordVersion++; } }
    public void revokeSessions() { passwordVersion++; }
}
