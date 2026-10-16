package br.com.roboparts.entity;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name = "team_invitations", schema = "roboparts")
public class TeamInvitation {
    @Id public UUID id;
    @Column(nullable=false, length=254) public String email;
    @Column(name="token_hash", nullable=false, unique=true, length=64) public String tokenHash;
    @Column(name="created_by", nullable=false) public UUID createdBy;
    @Column(name="created_at", nullable=false) public Instant createdAt;
    @Column(name="expires_at", nullable=false) public Instant expiresAt;
    @Column(name="consumed_at") public Instant consumedAt;
    @Column(nullable=false) public boolean revoked;
}
