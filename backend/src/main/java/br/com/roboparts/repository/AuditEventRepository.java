package br.com.roboparts.repository;

import br.com.roboparts.entity.AuditEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

// Only insert and user-scoped reads are exposed; events cannot be edited through this repository.
public interface AuditEventRepository extends Repository<AuditEvent, UUID> {
    AuditEvent save(AuditEvent event);
    List<AuditEvent> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);
}
