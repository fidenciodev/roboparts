package br.com.roboparts.repository;

import br.com.roboparts.entity.EmailVerification;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface EmailVerificationRepository extends JpaRepository<EmailVerification, UUID> {
    Optional<EmailVerification> findByUserId(UUID userId);
    @Query("select v.userId from EmailVerification v where v.id = :id")
    Optional<UUID> findUserIdById(UUID id);
}
