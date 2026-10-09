package br.com.roboparts.dto;

import br.com.roboparts.entity.AuditEvent;
import java.time.Instant;
import java.util.UUID;

public record ActivityResponse(UUID id, String action, Instant createdAt) {
    public static ActivityResponse from(AuditEvent event) {
        return new ActivityResponse(event.getId(), event.getAction().name(), event.getCreatedAt());
    }
}
