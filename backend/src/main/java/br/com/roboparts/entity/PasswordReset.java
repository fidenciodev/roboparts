package br.com.roboparts.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "password_resets", schema = "roboparts")
public class PasswordReset {
    @Id @Column(name = "user_id") public UUID userId;
    @Column(name = "code_hash", nullable = false, length = 255) public String codeHash;
    @Column(name = "expires_at", nullable = false) public Instant expiresAt;
    @Column(name = "last_sent_at", nullable = false) public Instant lastSentAt;
    @Column(nullable = false) public int attempts;
    @Column(name = "send_window_at", nullable = false) public Instant sendWindowAt;
    @Column(name = "send_count", nullable = false) public int sendCount;
}
