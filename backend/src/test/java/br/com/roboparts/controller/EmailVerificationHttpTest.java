package br.com.roboparts.controller;

import br.com.roboparts.exception.ApiRequestException;
import br.com.roboparts.support.BackendHttpTest;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MvcResult;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class EmailVerificationHttpTest extends BackendHttpTest {
    private static final String PASSWORD="Verification-test-password!";
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;

    @Test void registrationSendsOnlyTheEmailCodeAndLoginRequiresConfirmation() throws Exception {
        Pending pending=register();String code=lastCode(pending.email());
        assertThat(code).matches("[0-9]{6}");
        String hash=jdbc.queryForObject("SELECT code_hash FROM roboparts.email_verifications WHERE id=?",String.class,pending.id());
        assertThat(hash).isNotEqualTo(code);assertThat(encoder.matches(code,hash)).isTrue();
        assertThat(verified(pending.email())).isFalse();
        MvcResult first=login(pending.email(),PASSWORD,202);
        assertThat(JsonPath.<String>read(first.getResponse().getContentAsString(),"$.verificationId")).isEqualTo(pending.id().toString());
        assertThat(first.getRequest().getSession(false).getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)).isNull();
        mvc.perform(get("/api/auth/me").session((MockHttpSession)first.getRequest().getSession(false))).andExpect(status().isUnauthorized());
        assertThat(sendCount(pending.email())).isEqualTo(1); // A repeated login does not resend an active code.
        verifyCode(pending,code,200);assertThat(verified(pending.email())).isTrue();
        MvcResult authenticated=login(pending.email(),PASSWORD,200);
        mvc.perform(get("/api/auth/me").session((MockHttpSession)authenticated.getRequest().getSession(false)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value(pending.email()));
        verifyCode(pending,code,400);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM roboparts.audit_events a JOIN roboparts.users u ON u.id=a.user_id WHERE u.email=? AND a.action='USER_EMAIL_VERIFIED'",Integer.class,pending.email())).isEqualTo(1);
    }

    @Test void incorrectAttemptsArePersistedAndTheFifthAttemptBlocksEvenTheCorrectCode() throws Exception {
        Pending pending=register();String code=lastCode(pending.email());
        String wrong=code.equals("000000")?"000001":"000000";
        for(int i=1;i<=5;i++) {
            verifyCode(pending,wrong,400);
            assertThat(jdbc.queryForObject("SELECT attempts FROM roboparts.email_verifications WHERE id=?",Integer.class,pending.id())).isEqualTo(i);
        }
        verifyCode(pending,code,400);assertThat(verified(pending.email())).isFalse();
    }

    @Test void expirationAndResendInvalidateTheOldCodeAndKeepTheSameChallenge() throws Exception {
        Pending pending=register();String old=lastCode(pending.email());
        jdbc.update("UPDATE roboparts.email_verifications SET expires_at=?,last_sent_at=? WHERE id=?",Instant.now().minusSeconds(1),Instant.now().minusSeconds(61),pending.id());
        verifyCode(pending,old,400);
        MvcResult resent=post("/api/auth/resend-verification",idBody(pending),200);
        assertThat(JsonPath.<String>read(resent.getResponse().getContentAsString(),"$.verificationId")).isEqualTo(pending.id().toString());
        String latest=lastCode(pending.email());assertThat(latest).isNotEqualTo(old);
        verifyCode(pending,old,400);verifyCode(pending,latest,200);
        assertThat(sendCount(pending.email())).isEqualTo(2);
    }

    @Test void resendHasAnAccountCooldownAndAnHourlyLimit() throws Exception {
        Pending pending=register();post("/api/auth/resend-verification",idBody(pending),429);
        assertThat(sendCount(pending.email())).isEqualTo(1);
        for(int i=0;i<4;i++) {
            allowResend(pending);post("/api/auth/resend-verification",idBody(pending),200);
        }
        allowResend(pending);post("/api/auth/resend-verification",idBody(pending),429);
        assertThat(sendCount(pending.email())).isEqualTo(5);
        jdbc.update("UPDATE roboparts.email_verifications SET send_window_at=? WHERE id=?",Instant.now().minusSeconds(3601),pending.id());
        post("/api/auth/resend-verification",idBody(pending),200);
        assertThat(sendCount(pending.email())).isEqualTo(6);
    }

    @Test void mailFailureRollsBackPendingRegistrationAndDoesNotLeakSmtpDetails() throws Exception {
        String email=email();
        doThrow(new ApiRequestException(HttpStatus.SERVICE_UNAVAILABLE,"email_delivery_unavailable","Falha de envio."))
                .when(mailer).sendCode(eq(email),anyString());
        post("/api/auth/register",registrationBody(email),503);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM roboparts.users WHERE email=?",Integer.class,email)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM roboparts.email_verifications v JOIN roboparts.users u ON u.id=v.user_id WHERE u.email=?",Integer.class,email)).isZero();
    }

    @Test void failedResendPreservesThePreviousWorkingCode() throws Exception {
        Pending pending=register();String code=lastCode(pending.email());allowResend(pending);
        doThrow(new ApiRequestException(HttpStatus.SERVICE_UNAVAILABLE,"email_delivery_unavailable","Falha de envio."))
                .when(mailer).sendCode(eq(pending.email()),anyString());
        post("/api/auth/resend-verification",idBody(pending),503);
        assertThat(jdbc.queryForObject("SELECT send_count FROM roboparts.email_verifications WHERE id=?",Integer.class,pending.id())).isEqualTo(1);
        verifyCode(pending,code,200);
    }

    @Test void simultaneousConfirmationsConsumeTheCodeOnlyOnce() throws Exception {
        Pending pending=register();String code=lastCode(pending.email());CountDownLatch start=new CountDownLatch(1);
        try(var workers=Executors.newFixedThreadPool(2)) {
            var results=new ArrayList<Future<Integer>>();
            for(int i=0;i<2;i++)results.add(workers.submit(()->{start.await();return post("/api/auth/verify-email",codeBody(pending,code),null).getResponse().getStatus();}));
            start.countDown();assertThat(List.of(results.get(0).get(30,TimeUnit.SECONDS),results.get(1).get(30,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,400);
        }
    }

    @Test void incorrectPasswordCannotSendACodeOrRevealTheChallenge() throws Exception {
        Pending pending=register();login(pending.email(),"Incorrect-test-password!",401);login(email(),PASSWORD,401);
        assertThat(sendCount(pending.email())).isEqualTo(1);
    }

    @Test void existingUnverifiedAccountsReceiveACodeAfterThePasswordIsChecked() throws Exception {
        String email=email();jdbc.update("INSERT INTO roboparts.users(id,name,email,password_hash,created_at) VALUES (?,?,?,?,?)",
                UUID.randomUUID(),"Funcionário existente",email,encoder.encode(PASSWORD),Instant.now().minusSeconds(86400));
        MvcResult response=login(email,PASSWORD,202);
        Pending pending=new Pending(UUID.fromString(JsonPath.read(response.getResponse().getContentAsString(),"$.verificationId")),email);
        assertThat(verified(email)).isFalse();verifyCode(pending,lastCode(email),200);login(email,PASSWORD,200);
    }

    @Test void verificationWritesRequireCsrfAndRejectMalformedOrUnknownChallenges() throws Exception {
        Pending pending=register();
        for(String path:List.of("/api/auth/verify-email","/api/auth/resend-verification"))
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_JSON).content(idBody(pending)))
                    .andExpect(status().isForbidden());
        verifyCode(pending,"12345",400);verifyCode(pending,"1234567",400);verifyCode(pending,"abcdef",400);
        assertThat(jdbc.queryForObject("SELECT attempts FROM roboparts.email_verifications WHERE id=?",Integer.class,pending.id())).isZero();
        Pending unknown=new Pending(UUID.randomUUID(),pending.email());verifyCode(unknown,"123456",400);
        post("/api/auth/resend-verification",idBody(unknown),400);
    }

    private Pending register() throws Exception {
        String email=email();MvcResult result=post("/api/auth/register",registrationBody(email),201);
        assertThat(JsonPath.<Object>read(result.getResponse().getContentAsString(),"$")).isInstanceOf(Map.class);
        assertThat(result.getResponse().getContentAsString()).doesNotContain("codeHash","passwordHash",PASSWORD,"Test-only-invitation-code-2026-10");
        return new Pending(UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(),"$.verificationId")),email);
    }
    private MvcResult verifyCode(Pending pending,String code,int status) throws Exception {return post("/api/auth/verify-email",codeBody(pending,code),status);}
    private MvcResult login(String email,String password,int status) throws Exception {
        return post("/api/auth/login","{\"email\":\""+email+"\",\"password\":\""+password+"\"}",status);
    }
    private MvcResult post(String path,String body,Integer expected) throws Exception {
        MvcResult csrf=mvc.perform(get("/api/auth/csrf")).andReturn();
        String token=JsonPath.read(csrf.getResponse().getContentAsString(),"$.token");
        var action=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                .session((MockHttpSession)csrf.getRequest().getSession(false)).header("X-CSRF-TOKEN",token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
        if(expected!=null)action.andExpect(status().is(expected));
        return action.andReturn();
    }
    private boolean verified(String email) {return Boolean.TRUE.equals(jdbc.queryForObject("SELECT email_verified FROM roboparts.users WHERE email=?",Boolean.class,email));}
    private void allowResend(Pending pending) {jdbc.update("UPDATE roboparts.email_verifications SET last_sent_at=? WHERE id=?",Instant.now().minusSeconds(61),pending.id());}
    private String lastCode(String email) {
        return mockingDetails(mailer).getInvocations().stream().filter(i->i.getMethod().getName().equals("sendCode")&&i.getArgument(0).equals(email))
                .reduce((first,last)->last).orElseThrow().getArgument(1);
    }
    private long sendCount(String email) {return mockingDetails(mailer).getInvocations().stream().filter(i->i.getMethod().getName().equals("sendCode")&&i.getArgument(0).equals(email)).count();}
    private static String email() {return "verify-"+UUID.randomUUID()+"@example.com";}
    private static String registrationBody(String email) {return "{\"name\":\"Ana Silva\",\"email\":\""+email+"\",\"password\":\""+PASSWORD+"\",\"accessCode\":\"Test-only-invitation-code-2026-10\"}";}
    private static String idBody(Pending pending) {return "{\"verificationId\":\""+pending.id()+"\"}";}
    private static String codeBody(Pending pending,String code) {return "{\"verificationId\":\""+pending.id()+"\",\"code\":\""+code+"\"}";}
    private record Pending(UUID id,String email) { }
}
