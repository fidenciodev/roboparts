package br.com.roboparts.service;

import br.com.roboparts.dto.MfaDtos.*;
import br.com.roboparts.entity.*;
import br.com.roboparts.exception.ApiRequestException;
import br.com.roboparts.repository.*;
import br.com.roboparts.security.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MfaService {
    private final EmployeeUserRepository users; private final MfaCredentialRepository credentials;
    private final AuditEventRepository audit; private final PasswordEncoder passwords; private final SecuritySecrets secrets;
    private final Clock clock; private final JdbcTemplate jdbc; private final LoginAttemptLimiter limiter; private final SecurityMonitor monitor;
    public MfaService(EmployeeUserRepository users,MfaCredentialRepository credentials,AuditEventRepository audit,
            PasswordEncoder passwords,SecuritySecrets secrets,Clock clock,JdbcTemplate jdbc,LoginAttemptLimiter limiter,SecurityMonitor monitor) {
        this.users=users;this.credentials=credentials;this.audit=audit;this.passwords=passwords;this.secrets=secrets;
        this.clock=clock;this.jdbc=jdbc;this.limiter=limiter;this.monitor=monitor;
    }
    @Transactional(readOnly=true) public boolean enabled(UUID id) { return credentials.findById(id).map(c->c.enabled).orElse(false); }
    @Transactional(readOnly=true) public StatusResponse status(UUID id) {
        return new StatusResponse(enabled(id),jdbc.queryForObject("SELECT COUNT(*) FROM roboparts.mfa_recovery_codes WHERE user_id=? AND used_at IS NULL",Long.class,id));
    }
    @Transactional(noRollbackFor=ApiRequestException.class)
    public SetupResponse setup(SessionUser actor,String password) {
        EmployeeUser user=locked(actor.id(),actor.passwordVersion()); verifyPassword(user,password);
        MfaCredential record=credentials.findById(actor.id()).orElse(null);
        if(record!=null&&record.enabled)throw new ApiRequestException(HttpStatus.CONFLICT,"mfa_already_enabled","A autenticação em duas etapas já está ativa.");
        String secret=Totp.newSecret();
        if(record==null) { record=new MfaCredential();record.userId=user.getId(); }
        record.encryptedSecret=secrets.encrypt(secret,user.getId());record.enabled=false;record.lastCounter=-1;
        record.enrollmentExpiresAt=clock.instant().plusSeconds(600);record.failureWindowAt=clock.instant();record.failures=0;
        credentials.saveAndFlush(record);
        String label=URLEncoder.encode("RoboParts:"+user.getEmail(),StandardCharsets.UTF_8).replace("+","%20");
        return new SetupResponse(secret,"otpauth://totp/"+label+"?secret="+secret+"&issuer=RoboParts&algorithm=SHA1&digits=6&period=30",record.enrollmentExpiresAt);
    }
    @Transactional(noRollbackFor=ApiRequestException.class)
    public Enabled confirm(SessionUser actor,String code) {
        EmployeeUser user=locked(actor.id(),actor.passwordVersion());
        MfaCredential record=credentials.findById(user.getId()).orElseThrow(this::invalid);
        if(record.enabled||!record.enrollmentExpiresAt.isAfter(clock.instant()))throw invalid();
        verifyCode(user,record,code,false);record.enabled=true;user.revokeSessions();users.flush();
        jdbc.update("DELETE FROM roboparts.mfa_recovery_codes WHERE user_id=?",user.getId());
        List<String> codes=new ArrayList<>();SecureRandom random=new SecureRandom();
        for(int i=0;i<8;i++) {
            byte[] bytes=new byte[16];random.nextBytes(bytes);String raw=HexFormat.of().formatHex(bytes);
            jdbc.update("INSERT INTO roboparts.mfa_recovery_codes(user_id,code_hash) VALUES (?,?)",user.getId(),SecuritySecrets.digest(raw));
            codes.add(raw.replaceAll("(.{4})(?!$)","$1-"));
        }
        audit.save(new AuditEvent(UUID.randomUUID(),user.getId(),AuditAction.MFA_ENABLED,clock.instant()));
        return new Enabled(profile(user),List.copyOf(codes));
    }
    @Transactional(noRollbackFor=ApiRequestException.class)
    public SessionUser disable(SessionUser actor,String password,String code) {
        EmployeeUser user=locked(actor.id(),actor.passwordVersion());verifyPassword(user,password);
        MfaCredential record=credentials.findById(user.getId()).orElseThrow(this::invalid);
        if(!record.enabled)throw invalid();verifyCode(user,record,code,true);
        credentials.delete(record);jdbc.update("DELETE FROM roboparts.mfa_recovery_codes WHERE user_id=?",user.getId());
        user.revokeSessions();users.flush();audit.save(new AuditEvent(UUID.randomUUID(),user.getId(),AuditAction.MFA_DISABLED,clock.instant()));
        return profile(user);
    }
    @Transactional(noRollbackFor=ApiRequestException.class)
    public SessionUser login(UUID id,long version,String code) {
        EmployeeUser user=locked(id,version);MfaCredential record=credentials.findById(id).orElseThrow(this::invalid);
        if(!record.enabled)throw invalid();verifyCode(user,record,code,true);
        audit.save(new AuditEvent(UUID.randomUUID(),id,AuditAction.USER_LOGGED_IN,clock.instant()));return profile(user);
    }
    private void verifyPassword(EmployeeUser user,String password) {
        var ticket=limiter.acquire(user.getEmail());
        if(!ticket.allowed())throw limited();
        if(!passwords.matches(password,user.getPasswordHash())) { monitor.record("MFA_PASSWORD_FAILED",user.getEmail());throw invalid(); }
        limiter.success(ticket);
    }
    private void verifyCode(EmployeeUser user,MfaCredential record,String code,boolean allowRecovery) {
        Instant now=clock.instant();
        if(!record.failureWindowAt.plusSeconds(300).isAfter(now)) { record.failureWindowAt=now;record.failures=0; }
        if(record.failures>=5)throw limited();
        long step=Totp.match(secrets.decrypt(record.encryptedSecret,user.getId()),code,now,record.lastCounter);
        if(step>=0) { record.lastCounter=step;record.failures=0;return; }
        String normalized=code.replace("-","").replace(" ","").toLowerCase(Locale.ROOT);
        if(allowRecovery&&normalized.matches("[0-9a-f]{32}")&&jdbc.update(
                "UPDATE roboparts.mfa_recovery_codes SET used_at=? WHERE user_id=? AND code_hash=? AND used_at IS NULL",now,user.getId(),SecuritySecrets.digest(normalized))==1) {
            record.failures=0;return;
        }
        record.failures++;monitor.record("MFA_FAILED",user.getEmail());throw invalid();
    }
    private EmployeeUser locked(UUID id,long version) {
        EmployeeUser user=users.lockById(id).orElseThrow(this::invalid);
        if(!user.isActive()||!user.isEmailVerified()||user.getPasswordVersion()!=version)throw invalid();return user;
    }
    private SessionUser profile(EmployeeUser user) { return new SessionUser(user.getId(),user.getName(),user.getEmail(),user.getPasswordVersion()); }
    private ApiRequestException invalid() { return new ApiRequestException(HttpStatus.BAD_REQUEST,"mfa_invalid","Confira sua senha e o código do autenticador ou um código de recuperação ainda não usado."); }
    private ApiRequestException limited() { return new ApiRequestException(HttpStatus.TOO_MANY_REQUESTS,"mfa_rate_limited","Muitas tentativas. Aguarde cinco minutos antes de tentar novamente."); }
    public record Enabled(SessionUser user,List<String> recoveryCodes) {
        @Override public String toString() { return "Enabled[códigos omitidos]"; }
    }
}
