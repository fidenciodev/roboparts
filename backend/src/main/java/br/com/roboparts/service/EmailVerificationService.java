package br.com.roboparts.service;

import br.com.roboparts.dto.EmailVerificationDtos.VerificationResponse;
import br.com.roboparts.dto.UserResponse;
import br.com.roboparts.entity.*;
import br.com.roboparts.exception.ApiRequestException;
import br.com.roboparts.repository.*;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmailVerificationService {
    private final EmployeeUserRepository users;
    private final EmailVerificationRepository codes;
    private final AuditEventRepository audit;
    private final PasswordEncoder encoder;
    private final VerificationMailer mailer;
    private final Clock clock;
    private final SecureRandom random=new SecureRandom();
    public EmailVerificationService(EmployeeUserRepository users, EmailVerificationRepository codes,
            AuditEventRepository audit, PasswordEncoder encoder, VerificationMailer mailer, Clock clock) {
        this.users=users;this.codes=codes;this.audit=audit;this.encoder=encoder;this.mailer=mailer;this.clock=clock;
    }
    @Transactional(readOnly=true)
    public boolean verified(UUID userId) { return users.findById(userId).orElseThrow(this::invalid).isEmailVerified(); }
    @Transactional
    public VerificationResponse request(UUID userId) {
        EmployeeUser user=users.lockById(userId).orElseThrow(this::invalid);
        if(user.isEmailVerified()||!user.isActive())throw invalid();
        EmailVerification record=codes.findByUserId(userId).orElse(null);
        if(record!=null&&record.expiresAt.isAfter(clock.instant())&&record.attempts<5)
            return response(record,user);
        return issue(user,record);
    }
    @Transactional
    public VerificationResponse resend(UUID id) {
        EmployeeUser user=lockedUser(id);
        if(user.isEmailVerified()||!user.isActive())throw invalid();
        return issue(user,codes.findById(id).orElseThrow(this::invalid));
    }
    // Incorrect attempts must be committed even though the response is an error.
    @Transactional(noRollbackFor=ApiRequestException.class)
    public UserResponse verify(UUID id, String code) {
        EmployeeUser user=lockedUser(id);
        EmailVerification record=codes.findById(id).orElseThrow(this::invalid);
        if(!user.isActive()||user.isEmailVerified()||record.attempts>=5||!record.expiresAt.isAfter(clock.instant()))throw invalid();
        record.attempts++;
        if(!encoder.matches(code,record.codeHash))throw invalid();
        user.verifyEmail();users.flush();codes.delete(record);
        audit.save(new AuditEvent(UUID.randomUUID(),user.getId(),AuditAction.USER_EMAIL_VERIFIED,clock.instant()));
        return UserResponse.from(user);
    }
    private EmployeeUser lockedUser(UUID id) {
        // Read only the scalar ID before acquiring the user lock; avoid stale managed entities.
        UUID userId=codes.findUserIdById(id).orElseThrow(this::invalid);
        return users.lockById(userId).orElseThrow(this::invalid);
    }
    private VerificationResponse issue(EmployeeUser user, EmailVerification record) {
        Instant now=clock.instant();
        if(record==null) {
            record=new EmailVerification();record.id=UUID.randomUUID();record.userId=user.getId();
            record.sendWindowAt=now;record.sendCount=0;
        } else {
            if(record.lastSentAt.plusSeconds(60).isAfter(now))throw new ApiRequestException(HttpStatus.TOO_MANY_REQUESTS,
                    "verification_cooldown","Aguarde um minuto antes de reenviar o código.");
            if(!record.sendWindowAt.plusSeconds(3600).isAfter(now)){record.sendWindowAt=now;record.sendCount=0;}
            if(record.sendCount>=5)throw new ApiRequestException(HttpStatus.TOO_MANY_REQUESTS,
                    "verification_send_limit","O limite de envios foi atingido. Aguarde uma hora e tente novamente.");
        }
        String code;
        do {code=String.format(Locale.ROOT,"%06d",random.nextInt(1_000_000));}
        while(record.codeHash!=null&&encoder.matches(code,record.codeHash));
        record.codeHash=encoder.encode(code);record.attempts=0;record.expiresAt=now.plusSeconds(600);
        record.lastSentAt=now;record.sendCount++;
        codes.saveAndFlush(record);
        mailer.sendCode(user.getEmail(),code);
        return response(record,user);
    }
    private VerificationResponse response(EmailVerification record, EmployeeUser user) {
        Instant now=clock.instant();
        return new VerificationResponse(record.id,user.getEmail(),Math.max(0,Duration.between(now,record.expiresAt).toSeconds()),
                Math.max(0,60-Duration.between(record.lastSentAt,now).toSeconds()));
    }
    private ApiRequestException invalid() { return new ApiRequestException(HttpStatus.BAD_REQUEST,"verification_invalid",
            "Código inválido, expirado ou sem tentativas disponíveis. Solicite um novo código."); }
}
