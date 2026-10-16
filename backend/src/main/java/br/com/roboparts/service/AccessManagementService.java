package br.com.roboparts.service;

import br.com.roboparts.entity.*;
import br.com.roboparts.exception.ApiRequestException;
import br.com.roboparts.repository.*;
import br.com.roboparts.security.*;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccessManagementService {
    private final AccessManagementPolicy policy; private final TeamInvitationRepository invitations;
    private final EmployeeUserRepository users; private final AuditEventRepository audit;
    private final RegistrationAccessCode bootstrap; private final Clock clock; private final boolean strict;
    public AccessManagementService(AccessManagementPolicy policy, TeamInvitationRepository invitations,
            EmployeeUserRepository users, AuditEventRepository audit, RegistrationAccessCode bootstrap, Clock clock,
            @Value("${app.registration.require-invitation:true}") boolean strict) {
        this.policy = policy; this.invitations = invitations; this.users = users; this.audit = audit;
        this.bootstrap = bootstrap; this.clock = clock; this.strict = strict;
    }
    @Transactional
    public void consume(String email, String token) {
        TeamInvitation invite = invitations.lockByHash(SecuritySecrets.digest(token)).orElse(null);
        if (invite != null) {
            if (invite.revoked || invite.consumedAt != null || !invite.expiresAt.isAfter(clock.instant()) || !invite.email.equals(email)) throw invalid();
            invite.consumedAt = clock.instant(); return;
        }
        // Only the configured mailbox can bootstrap a manager; email ownership is still mandatory.
        if (!strict || policy.configuredManager(email)) { bootstrap.verify(token); return; }
        throw invalid();
    }
    @Transactional
    public CreatedInvitation create(SessionUser actor, String email) {
        policy.requireManager(actor);
        if (users.existsByEmail(email)) throw new ApiRequestException(HttpStatus.CONFLICT, "email_already_registered", "O funcionário já possui uma conta.");
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        TeamInvitation invitation = new TeamInvitation(); invitation.id = UUID.randomUUID(); invitation.email = email;
        invitation.tokenHash = SecuritySecrets.digest(token); invitation.createdBy = actor.id(); invitation.createdAt = clock.instant();
        invitation.expiresAt = clock.instant().plus(Duration.ofDays(3)); invitations.saveAndFlush(invitation);
        audit.save(new AuditEvent(UUID.randomUUID(), actor.id(), AuditAction.INVITATION_CREATED, clock.instant()));
        return new CreatedInvitation(invitation.id, email, token, invitation.expiresAt);
    }
    @Transactional
    public void revoke(SessionUser actor, UUID id) {
        policy.requireManager(actor); TeamInvitation invitation = invitations.findById(id).orElseThrow(this::invalid);
        invitation.revoked = true;
        audit.save(new AuditEvent(UUID.randomUUID(), actor.id(), AuditAction.INVITATION_REVOKED, clock.instant()));
    }
    @Transactional
    public void active(SessionUser actor, UUID id, boolean active) {
        policy.requireManager(actor);
        if (actor.id().equals(id)) throw new ApiRequestException(HttpStatus.BAD_REQUEST, "cannot_disable_self", "Você não pode bloquear sua própria conta.");
        EmployeeUser employee = users.lockById(id).orElseThrow(this::invalid);
        employee.setActive(active); users.flush();
        audit.save(new AuditEvent(UUID.randomUUID(), actor.id(), active ? AuditAction.USER_ENABLED : AuditAction.USER_DISABLED, clock.instant()));
    }
    @Transactional(readOnly=true)
    public List<Employee> employees(SessionUser actor) {
        policy.requireManager(actor);
        return users.findAll(org.springframework.data.domain.PageRequest.of(0, 1000, org.springframework.data.domain.Sort.by("name"))).stream()
                .map(user -> new Employee(user.getId(), user.getName(), user.getEmail(), user.isActive(), user.isEmailVerified())).toList();
    }
    @Transactional(readOnly=true)
    public List<Invitation> invitations(SessionUser actor) {
        policy.requireManager(actor);
        return invitations.findTop100ByOrderByCreatedAtDesc().stream().map(i -> new Invitation(i.id, i.email, i.expiresAt, i.revoked, i.consumedAt != null)).toList();
    }
    private ApiRequestException invalid() { return new ApiRequestException(HttpStatus.BAD_REQUEST, "invalid_access_code", "Convite inválido, usado, revogado ou expirado. Solicite outro ao responsável."); }
    public record Employee(UUID id, String name, String email, boolean active, boolean verified) { }
    public record Invitation(UUID id, String email, Instant expiresAt, boolean revoked, boolean consumed) { }
    public record CreatedInvitation(UUID id, String email, String token, Instant expiresAt) {
        @Override public String toString() { return "CreatedInvitation[convite omitido]"; }
    }
}
