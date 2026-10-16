package br.com.roboparts.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import br.com.roboparts.security.*;
import br.com.roboparts.support.BackendHttpTest;
import com.jayway.jsonpath.JsonPath;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;

@TestPropertySource(properties={"app.registration.require-invitation=true","app.auth.account-limit.attempts=5"})
class AccountSecurityHttpTest extends BackendHttpTest {
    static final String PASSWORD="A-long-test-password-for-security!";
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @MockitoBean Clock clock;
    Instant now;
    @BeforeEach void time(){at(Instant.parse("2026-10-16T12:00:00Z"));}
    void at(Instant time){now=time;when(clock.instant()).thenReturn(time);when(clock.millis()).thenReturn(time.toEpochMilli());}
    String email(){return UUID.randomUUID()+"@example.com";}
    UUID employee(String email){
        var existing=jdbc.queryForList("SELECT id FROM roboparts.users WHERE email=?",UUID.class,email);
        if(!existing.isEmpty())return existing.getFirst();
        UUID id=UUID.randomUUID();jdbc.update("INSERT INTO roboparts.users(id,name,email,password_hash,created_at,email_verified) VALUES (?,?,?,?,?,true)",id,"Employee",email,encoder.encode(PASSWORD),now);return id;
    }
    MockHttpSession csrf(MockHttpSession session) throws Exception {
        var builder=get("/api/auth/csrf");if(session!=null)builder.session(session);
        return (MockHttpSession)mvc.perform(builder).andExpect(status().isOk()).andReturn().getRequest().getSession();
    }
    MvcResult send(String path,MockHttpSession session,String body) throws Exception {
        MvcResult token=mvc.perform(get("/api/auth/csrf").session(session)).andExpect(status().isOk()).andReturn();
        String value=JsonPath.read(token.getResponse().getContentAsString(),"$.token");
        return mvc.perform(post(path).session(session).header("X-CSRF-TOKEN",value).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }
    MockHttpSession login(String email) throws Exception {
        MockHttpSession session=csrf(null);
        assertThat(send("/api/auth/login",session,"{\"email\":\""+email+"\",\"password\":\""+PASSWORD+"\"}").getResponse().getStatus()).isEqualTo(200);
        return session;
    }
    String json(MvcResult result){return new String(result.getResponse().getContentAsByteArray(),java.nio.charset.StandardCharsets.UTF_8);}
    String field(MvcResult result,String key){return JsonPath.read(json(result),"$."+key);}
    MvcResult register(String email,String token) throws Exception {
        return send("/api/auth/register",csrf(null),"{\"name\":\"New employee\",\"email\":\""+email+"\",\"password\":\""+PASSWORD+"\",\"accessCode\":\""+token+"\"}");
    }
    MockHttpSession manager() throws Exception {employee("manager@example.com");return login("manager@example.com");}
    @Test void onlyVerifiedConfiguredManagerCanManageAccessOrReadMonitoring() throws Exception {
        String email=email();employee(email);var ordinary=login(email);
        mvc.perform(get("/api/security").session(ordinary)).andExpect(status().isOk()).andExpect(jsonPath("$.manager").value(false));
        for(String path:List.of("employees","invitations","events"))mvc.perform(get("/api/security/"+path).session(ordinary)).andExpect(status().isForbidden());
        assertThat(send("/api/security/invitations",ordinary,"{\"email\":\"new@example.com\"}").getResponse().getStatus()).isEqualTo(403);
        var manager=manager();mvc.perform(get("/api/security").session(manager)).andExpect(jsonPath("$.manager").value(true));
        mvc.perform(get("/api/security/employees").session(manager)).andExpect(status().isOk()).andExpect(jsonPath("$[0].passwordHash").doesNotExist());
        mvc.perform(get("/api/security/events").session(manager)).andExpect(status().isOk()).andExpect(jsonPath("$.summary").isArray());
    }
    @Test void invitationsAreBoundToOneEmailStoredHashedAndConsumedOnlyOnce() throws Exception {
        var manager=manager();String email=email();
        var created=send("/api/security/invitations",manager,"{\"email\":\""+email+"\"}");
        assertThat(created.getResponse().getStatus()).isEqualTo(201);String token=field(created,"token");
        assertThat(token).hasSize(43);
        assertThat(jdbc.queryForObject("SELECT token_hash FROM roboparts.team_invitations WHERE id=?",String.class,UUID.fromString(field(created,"id")))).isEqualTo(SecuritySecrets.digest(token)).isNotEqualTo(token);
        assertThat(register(email(),token).getResponse().getStatus()).isEqualTo(400);
        assertThat(register(email,token).getResponse().getStatus()).isEqualTo(201);
        assertThat(register(email(),token).getResponse().getStatus()).isEqualTo(400);
        String listed=mvc.perform(get("/api/security/invitations").session(manager)).andReturn().getResponse().getContentAsString();
        assertThat(listed).doesNotContain(token,"tokenHash");
        assertThat(register(email(),"Test-only-invitation-code-2026-10").getResponse().getStatus()).isEqualTo(400);
    }
    @Test void revokedAndExpiredInvitationsCannotRegister() throws Exception {
        var manager=manager();String email=email();var created=send("/api/security/invitations",manager,"{\"email\":\""+email+"\"}");
        assertThat(send("/api/security/invitations/"+field(created,"id")+"/revoke",manager,"{}").getResponse().getStatus()).isEqualTo(204);
        assertThat(register(email,field(created,"token")).getResponse().getStatus()).isEqualTo(400);
        String second=email();created=send("/api/security/invitations",manager,"{\"email\":\""+second+"\"}");
        at(now.plus(Duration.ofDays(4)));
        assertThat(register(second,field(created,"token")).getResponse().getStatus()).isEqualTo(400);
    }
    @Test void disablingRevokesExistingSessionAndReactivationDoesNotRestoreIt() throws Exception {
        var manager=manager();String email=email();UUID id=employee(email);var old=login(email);
        assertThat(send("/api/security/employees/"+id,manager,"{\"active\":false}").getResponse().getStatus()).isEqualTo(204);
        mvc.perform(get("/api/auth/me").session(old)).andExpect(status().isUnauthorized());
        assertThat(send("/api/auth/login",csrf(null),"{\"email\":\""+email+"\",\"password\":\""+PASSWORD+"\"}").getResponse().getStatus()).isEqualTo(401);
        assertThat(send("/api/security/employees/"+id,manager,"{}").getResponse().getStatus()).isEqualTo(400);
        assertThat(send("/api/security/employees/"+id,manager,"{\"active\":true}").getResponse().getStatus()).isEqualTo(204);
        mvc.perform(get("/api/auth/me").session(login(email))).andExpect(status().isOk());
        UUID managerId=jdbc.queryForObject("SELECT id FROM roboparts.users WHERE email='manager@example.com'",UUID.class);
        assertThat(send("/api/security/employees/"+managerId,manager,"{\"active\":false}").getResponse().getStatus()).isEqualTo(400);
    }
    @Test void accountLimitAppliesAcrossSessionsAndUnknownAccountsEqually() throws Exception {
        String email=email();employee(email);
        for(int i=0;i<5;i++)assertThat(send("/api/auth/login",csrf(null),"{\"email\":\""+email+"\",\"password\":\"incorrect-password\"}").getResponse().getStatus()).isEqualTo(401);
        var denied=send("/api/auth/login",csrf(null),"{\"email\":\""+email.toUpperCase()+"\",\"password\":\""+PASSWORD+"\"}");
        assertThat(denied.getResponse().getStatus()).isEqualTo(429);assertThat(denied.getResponse().getHeader("Retry-After")).isNotBlank();
        String unknown=email();
        for(int i=0;i<5;i++)assertThat(send("/api/auth/login",csrf(null),"{\"email\":\""+unknown+"\",\"password\":\"incorrect-password\"}").getResponse().getStatus()).isEqualTo(401);
        assertThat(send("/api/auth/login",csrf(null),"{\"email\":\""+unknown+"\",\"password\":\"incorrect-password\"}").getResponse().getStatus()).isEqualTo(429);
        at(now.plusSeconds(901));login(email);
        mvc.perform(get("/api/security/events").session(manager())).andExpect(status().isOk()).andExpect(jsonPath("$.recent[0].account").isNotEmpty()).andExpect(jsonPath("$.recent[0].occurred_at").exists());
    }
    @Test void mfaRequiresBoundPasswordChallengeRejectsReplayAndConsumesRecoveryCodeOnce() throws Exception {
        String email=email();UUID id=employee(email);var current=login(email);var other=login(email);
        assertThat(send("/api/security/mfa/setup",current,"{\"password\":\"incorrect-password\"}").getResponse().getStatus()).isEqualTo(400);
        var setup=send("/api/security/mfa/setup",current,"{\"password\":\""+PASSWORD+"\"}");assertThat(setup.getResponse().getStatus()).isEqualTo(200);
        String secret=field(setup,"secret");
        assertThat(jdbc.queryForObject("SELECT encrypted_secret FROM roboparts.mfa_credentials WHERE user_id=?",String.class,id)).doesNotContain(secret);
        String otp=Totp.code(secret,now.getEpochSecond()/30,6);
        mvc.perform(post("/api/security/mfa/confirm").session(current).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\""+otp+"\"}")).andExpect(status().isForbidden());
        var enabled=send("/api/security/mfa/confirm",current,"{\"code\":\""+otp+"\"}");assertThat(enabled.getResponse().getStatus()).isEqualTo(200);
        List<String> recovery=JsonPath.read(json(enabled),"$.recoveryCodes");assertThat(recovery).hasSize(8).doesNotHaveDuplicates();
        assertThat(jdbc.queryForList("SELECT code_hash FROM roboparts.mfa_recovery_codes WHERE user_id=?",String.class,id)).doesNotContain(recovery.getFirst().replace("-",""));
        mvc.perform(get("/api/auth/me").session(current)).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").session(other)).andExpect(status().isUnauthorized());
        assertThat(send("/api/auth/mfa-login",csrf(null),"{\"code\":\""+recovery.getFirst()+"\"}").getResponse().getStatus()).isEqualTo(400);
        var pending=csrf(null);var challenge=send("/api/auth/login",pending,"{\"email\":\""+email+"\",\"password\":\""+PASSWORD+"\"}");
        assertThat(challenge.getResponse().getStatus()).isEqualTo(202);assertThat(JsonPath.<Boolean>read(json(challenge),"$.mfaRequired")).isTrue();
        mvc.perform(get("/api/auth/me").session(pending)).andExpect(status().isUnauthorized());
        assertThat(send("/api/auth/mfa-login",pending,"{\"code\":\""+otp+"\"}").getResponse().getStatus()).isEqualTo(400);
        assertThat(send("/api/auth/mfa-login",pending,"{\"code\":\""+recovery.getFirst()+"\"}").getResponse().getStatus()).isEqualTo(200);
        mvc.perform(get("/api/auth/me").session(pending)).andExpect(status().isOk());
        assertThat(send("/api/security/mfa/disable",pending,"{\"password\":\""+PASSWORD+"\",\"code\":\""+recovery.getFirst()+"\"}").getResponse().getStatus()).isEqualTo(400);
        assertThat(send("/api/security/mfa/disable",pending,"{\"password\":\""+PASSWORD+"\",\"code\":\""+recovery.get(1)+"\"}").getResponse().getStatus()).isEqualTo(204);
        mvc.perform(get("/api/security").session(pending)).andExpect(jsonPath("$.mfa.enabled").value(false));login(email);
    }
    @Test void mfaFailuresPersistAndExpiredPasswordChallengeCannotLogIn() throws Exception {
        String email=email();UUID id=employee(email);var session=login(email);
        var setup=send("/api/security/mfa/setup",session,"{\"password\":\""+PASSWORD+"\"}");String secret=field(setup,"secret");
        var confirm=send("/api/security/mfa/confirm",session,"{\"code\":\""+Totp.code(secret,now.getEpochSecond()/30,6)+"\"}");assertThat(confirm.getResponse().getStatus()).isEqualTo(200);
        var pending=csrf(null);send("/api/auth/login",pending,"{\"email\":\""+email+"\",\"password\":\""+PASSWORD+"\"}");
        for(int i=0;i<5;i++)assertThat(send("/api/auth/mfa-login",pending,"{\"code\":\"invalid-factor\"}").getResponse().getStatus()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT failures FROM roboparts.mfa_credentials WHERE user_id=?",Integer.class,id)).isEqualTo(5);
        assertThat(send("/api/auth/mfa-login",pending,"{\"code\":\"invalid-factor\"}").getResponse().getStatus()).isEqualTo(429);
        at(now.plusSeconds(301));
        assertThat(send("/api/auth/mfa-login",pending,"{\"code\":\""+Totp.code(secret,now.getEpochSecond()/30,6)+"\"}").getResponse().getStatus()).isEqualTo(400);
        mvc.perform(get("/api/auth/me").session(pending)).andExpect(status().isUnauthorized());
    }
}
