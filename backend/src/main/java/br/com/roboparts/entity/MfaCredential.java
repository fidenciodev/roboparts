package br.com.roboparts.entity;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name="mfa_credentials",schema="roboparts")
public class MfaCredential {
    @Id @Column(name="user_id") public UUID userId;
    @Column(name="encrypted_secret",nullable=false,length=512) public String encryptedSecret;
    @Column(nullable=false) public boolean enabled;
    @Column(name="last_counter",nullable=false) public long lastCounter = -1;
    @Column(name="enrollment_expires_at",nullable=false) public Instant enrollmentExpiresAt;
    @Column(name="failure_window_at",nullable=false) public Instant failureWindowAt;
    @Column(nullable=false) public int failures;
}
