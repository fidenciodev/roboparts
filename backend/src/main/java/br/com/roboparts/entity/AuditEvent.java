package br.com.roboparts.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "audit_events", schema = "roboparts")
public class AuditEvent {
    @Id
    private UUID id;
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40, updatable = false)
    private AuditAction action;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(nullable = false, length = 2000, updatable = false)
    private String details = "";
    @Column(name = "resource_type", length = 40, updatable = false)
    private String resourceType;
    @Column(name = "resource_id", updatable = false)
    private UUID resourceId;

    protected AuditEvent() { }
    public AuditEvent(UUID id, UUID userId, AuditAction action, Instant createdAt) {
        this.id = id;
        this.userId = userId;
        this.action = action;
        this.createdAt = createdAt;
    }
    public UUID getId() { return id; }
    public AuditEvent(UUID userId, AuditAction action, String details, String resourceType, UUID resourceId) {
        this(UUID.randomUUID(), userId, action, Instant.now());
        this.details = details;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
    }
    public UUID getUserId() { return userId; }
    public AuditAction getAction() { return action; }
    public Instant getCreatedAt() { return createdAt; }
}
