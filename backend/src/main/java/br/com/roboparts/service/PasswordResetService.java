package br.com.roboparts.service;

import br.com.roboparts.entity.*;
import br.com.roboparts.exception.ApiRequestException;
import br.com.roboparts.repository.*;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class PasswordResetService {
    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);
    private final EmployeeUserRepository users;
    private final PasswordResetRepository codes;
    private final EmailVerificationRepository verifications;
    private final AuditEventRepository audit;
    private final PasswordEncoder encoder;
    private final VerificationMailer mailer;
    private final TaskExecutor executor;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public PasswordResetService(EmployeeUserRepository users, PasswordResetRepository codes,
            EmailVerificationRepository verifications, AuditEventRepository audit, PasswordEncoder encoder,
            VerificationMailer mailer, Clock clock, @Qualifier("passwordResetExecutor") TaskExecutor executor) {
        this.users = users; this.codes = codes; this.verifications = verifications; this.audit = audit;
        this.encoder = encoder; this.mailer = mailer; this.clock = clock; this.executor = executor;
    }

    @Transactional
    public void request(String email) {
        UUID id = users.findIdByEmail(email).orElse(null);
        if (id == null) return;
        EmployeeUser user = users.lockById(id).orElseThrow(this::invalid);
        if (!user.isActive()) return;
        PasswordReset record = codes.findById(id).orElse(null);
        Instant now = clock.instant();
        if (record == null) {
            record = new PasswordReset(); record.userId = id; record.sendWindowAt = now;
        } else {
            if (record.lastSentAt.plusSeconds(60).isAfter(now)) return;
            if (!record.sendWindowAt.plusSeconds(3600).isAfter(now)) { record.sendWindowAt = now; record.sendCount = 0; }
            if (record.sendCount >= 5) return;
        }
        String code;
        do { code = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000)); }
        while (record.codeHash != null && encoder.matches(code, record.codeHash));
        record.codeHash = encoder.encode(code); record.attempts = 0;
        record.expiresAt = now.plusSeconds(600); record.lastSentAt = now; record.sendCount++;
        codes.saveAndFlush(record);
        mailer.sendPasswordResetCode(user.getEmail(), code);
    }

    @Transactional(noRollbackFor = ApiRequestException.class)
    public void reset(String email, String code, String password) {
        UUID id = users.findIdByEmail(email).orElseThrow(this::invalid);
        EmployeeUser user = users.lockById(id).orElseThrow(this::invalid);
        if (!user.isActive()) throw invalid();
        PasswordReset record = codes.findById(id).orElseThrow(this::invalid);
        if (record.attempts >= 5 || !record.expiresAt.isAfter(clock.instant())) throw invalid();
        record.attempts++;
        if (!encoder.matches(code, record.codeHash)) throw invalid();
        user.resetPassword(encoder.encode(password));
        if (!user.isEmailVerified()) {
            user.verifyEmail();
            audit.save(new AuditEvent(UUID.randomUUID(), id, AuditAction.USER_EMAIL_VERIFIED, clock.instant()));
        }
        verifications.findByUserId(id).ifPresent(verifications::delete);
        // Retain send counters after consuming the code, so a successful reset cannot bypass quotas.
        record.expiresAt = Instant.EPOCH; record.attempts = 5;
        users.flush();
        audit.save(new AuditEvent(UUID.randomUUID(), id, AuditAction.USER_PASSWORD_RESET, clock.instant()));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try {
                    executor.execute(() -> {
                        try { mailer.sendPasswordChanged(email); }
                        catch (RuntimeException exception) { log.warn("Não foi possível enviar um aviso de senha alterada."); }
                    });
                } catch (RuntimeException exception) { log.warn("Não foi possível agendar um aviso de senha alterada."); }
            }
        });
    }

    private ApiRequestException invalid() {
        return new ApiRequestException(HttpStatus.BAD_REQUEST, "password_reset_invalid",
                "Código inválido, expirado ou sem tentativas disponíveis. Solicite outro código.");
    }
}
