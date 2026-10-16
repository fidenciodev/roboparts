package br.com.roboparts.controller;

import br.com.roboparts.exception.ApiRequestException;
import br.com.roboparts.support.BackendHttpTest;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PasswordResetHttpTest extends BackendHttpTest {
    private static final String OLD = "Old-password-for-reset!";
    private static final String NEW = "New-password-for-reset!";
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @MockitoBean(name = "passwordResetExecutor") TaskExecutor executor;

    @BeforeEach void runQueuedTasksLocally() {
        doAnswer(invocation -> { invocation.getArgument(0, Runnable.class).run(); return null; }).when(executor).execute(any());
    }

    @Test void recoveryIsQueuedAndReturnsTheSameResponseForUnknownAndKnownAddresses() throws Exception {
        String email = account(true);
        AtomicReference<Runnable> queued = new AtomicReference<>();
        doAnswer(invocation -> { queued.set(invocation.getArgument(0)); return null; }).when(executor).execute(any());
        MvcResult known = forgot("  " + email.toUpperCase(Locale.ROOT) + "  ");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM roboparts.password_resets WHERE user_id=?", Integer.class, id(email))).isZero();
        verifyNoInteractions(mailer);
        queued.get().run();
        assertThat(lastCode(email)).matches("[0-9]{6}");
        MvcResult unknown = forgot("missing-" + UUID.randomUUID() + "@example.com");
        assertThat(known.getResponse().getContentAsString()).isEqualTo(unknown.getResponse().getContentAsString());
        assertThat(known.getResponse().getContentAsString()).doesNotContain(email, "userId", "codeHash", lastCode(email));
    }

    @Test void resetsOnceStoresOnlyHashRevokesEveryOldSessionAndRequiresNewPasswordLogin() throws Exception {
        String email = account(true);
        MockHttpSession first = session(login(email, OLD, 200));
        MockHttpSession second = session(login(email, OLD, 200));
        forgot(email);
        String code = lastCode(email);
        String hash = jdbc.queryForObject("SELECT code_hash FROM roboparts.password_resets WHERE user_id=?", String.class, id(email));
        assertThat(hash).isNotEqualTo(code); assertThat(encoder.matches(code, hash)).isTrue();
        reset(email, code, NEW, 204);
        String passwordHash = jdbc.queryForObject("SELECT password_hash FROM roboparts.users WHERE email=?", String.class, email);
        assertThat(passwordHash).isNotEqualTo(NEW); assertThat(encoder.matches(NEW, passwordHash)).isTrue();
        assertThat(jdbc.queryForObject("SELECT password_version FROM roboparts.users WHERE email=?", Long.class, email)).isEqualTo(1);
        mvc.perform(get("/api/auth/me").session(first)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").session(second)).andExpect(status().isUnauthorized());
        assertThat(first.isInvalid()).isTrue(); assertThat(second.isInvalid()).isTrue();
        login(email, OLD, 401);
        MockHttpSession fresh = session(login(email, NEW, 200));
        mvc.perform(get("/api/auth/me").session(fresh)).andExpect(status().isOk());
        reset(email, code, "Yet-another-password!", 400);
        verify(mailer).sendPasswordChanged(email);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM roboparts.audit_events WHERE user_id=? AND action='USER_PASSWORD_RESET'", Integer.class, id(email))).isEqualTo(1);
    }

    @Test void wrongAttemptsCommitAndFiveFailuresExhaustTheCodeWithoutChangingPassword() throws Exception {
        String email = account(true); forgot(email); String correct = lastCode(email);
        String wrong = correct.equals("000000") ? "000001" : "000000";
        for (int attempt = 1; attempt <= 5; attempt++) {
            reset(email, wrong, NEW, 400);
            assertThat(jdbc.queryForObject("SELECT attempts FROM roboparts.password_resets WHERE user_id=?", Integer.class, id(email))).isEqualTo(attempt);
        }
        reset(email, correct, NEW, 400); login(email, OLD, 200);
        verify(mailer, never()).sendPasswordChanged(anyString());
    }

    @Test void expirationResendCooldownAndHourlyLimitAreEnforcedWithoutDisclosingAccounts() throws Exception {
        String email = account(true); forgot(email); String old = lastCode(email);
        forgot(email); assertThat(sendCount(email)).isEqualTo(1);
        jdbc.update("UPDATE roboparts.password_resets SET expires_at=? WHERE user_id=?", Instant.now().minusSeconds(1), id(email));
        reset(email, old, NEW, 400);
        allowResend(email); forgot(email); String latest = lastCode(email);
        assertThat(latest).isNotEqualTo(old); reset(email, old, NEW, 400);
        for (int i = 0; i < 3; i++) { allowResend(email); forgot(email); }
        allowResend(email); forgot(email); assertThat(sendCount(email)).isEqualTo(5);
        jdbc.update("UPDATE roboparts.password_resets SET send_window_at=? WHERE user_id=?", Instant.now().minusSeconds(3601), id(email));
        forgot(email); assertThat(sendCount(email)).isEqualTo(6); reset(email, lastCode(email), NEW, 204);
        forgot(email); assertThat(sendCount(email)).isEqualTo(6); // Consuming a code must not reset quotas.
    }

    @Test void smtpFailurePreservesThePreviousCodeAndThePublicRequestRemainsGeneric() throws Exception {
        String email = account(true); forgot(email); String previous = lastCode(email); allowResend(email);
        doThrow(new ApiRequestException(HttpStatus.SERVICE_UNAVAILABLE, "email_delivery_unavailable", "test secret"))
                .when(mailer).sendPasswordResetCode(eq(email), anyString());
        MvcResult response = forgot(email);
        assertThat(response.getResponse().getContentAsString()).doesNotContain("test secret", "email_delivery_unavailable");
        assertThat(jdbc.queryForObject("SELECT send_count FROM roboparts.password_resets WHERE user_id=?", Integer.class, id(email))).isEqualTo(1);
        reset(email, previous, NEW, 204);
    }

    @Test void codeProvesEmailOwnershipAndClearsPendingVerificationButDoesNotCreateASession() throws Exception {
        String email = account(false); forgot(email);
        UUID verification = UUID.randomUUID();
        jdbc.update("INSERT INTO roboparts.email_verifications(id,user_id,code_hash,expires_at,last_sent_at,attempts,send_window_at,send_count) VALUES (?,?,?,?,?,?,?,?)",
                verification, id(email), encoder.encode("111111"), Instant.now().plusSeconds(600), Instant.now(), 0, Instant.now(), 1);
        jdbc.update("UPDATE roboparts.password_resets SET code_hash=? WHERE user_id=?", encoder.encode("222222"), id(email));
        reset(email, "111111", NEW, 400); // A registration code cannot authorize a password reset.
        MvcResult completed = reset(email, "222222", NEW, 204);
        assertThat(jdbc.queryForObject("SELECT email_verified FROM roboparts.users WHERE email=?", Boolean.class, email)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM roboparts.email_verifications WHERE user_id=?", Integer.class, id(email))).isZero();
        mvc.perform(get("/api/auth/me").session(session(completed))).andExpect(status().isUnauthorized());
        login(email, NEW, 200);
    }

    @Test void aFailedNotificationAfterCommitDoesNotUndoTheNewPassword() throws Exception {
        String email = account(true); forgot(email);
        doThrow(new ApiRequestException(HttpStatus.SERVICE_UNAVAILABLE, "email_delivery_unavailable", "private SMTP diagnostic"))
                .when(mailer).sendPasswordChanged(email);
        reset(email, lastCode(email), NEW, 204);
        login(email, NEW, 200); login(email, OLD, 401);
    }

    @Test void simultaneousResetsCanConsumeACodeOnlyOnce() throws Exception {
        String email = account(true); forgot(email); String code = lastCode(email);
        CountDownLatch start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) results.add(workers.submit(() -> {
                start.await(); return reset(email, code, NEW, null).getResponse().getStatus();
            }));
            start.countDown();
            assertThat(List.of(results.get(0).get(30, TimeUnit.SECONDS), results.get(1).get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(204, 400);
        }
    }

    @Test void bothEndpointsRequireCsrfAndMalformedValuesNeverChangeTheAccount() throws Exception {
        String email = account(true); forgot(email);
        for (String path : List.of("/api/auth/forgot-password", "/api/auth/reset-password")) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + email + "\"}"))
                    .andExpect(status().isForbidden());
        }
        reset(email, "12345", NEW, 400); reset(email, "abcdef", NEW, 400);
        reset(email, lastCode(email), "short", 400);
        reset("missing@example.com", "123456", NEW, 400);
        assertThat(jdbc.queryForObject("SELECT attempts FROM roboparts.password_resets WHERE user_id=?", Integer.class, id(email))).isZero();
        login(email, OLD, 200);
    }

    @Test void aSaturatedQueueRejectsRequestsWithoutRevealingAccountExistence() throws Exception {
        doThrow(new TaskRejectedException("private queue diagnostic")).when(executor).execute(any());
        MvcResult result = post("/api/auth/forgot-password", "{\"email\":\"missing@example.com\"}", 503);
        assertThat(result.getResponse().getContentAsString()).contains("service_unavailable").doesNotContain("private queue diagnostic");
    }

    private String account(boolean verified) {
        String email = "reset-" + UUID.randomUUID() + "@example.com";
        jdbc.update("INSERT INTO roboparts.users(id,name,email,password_hash,created_at,email_verified) VALUES (?,?,?,?,?,?)",
                UUID.randomUUID(), "Ana Silva", email, encoder.encode(OLD), Instant.now(), verified);
        return email;
    }
    private UUID id(String email) { return jdbc.queryForObject("SELECT id FROM roboparts.users WHERE email=?", UUID.class, email); }
    private void allowResend(String email) { jdbc.update("UPDATE roboparts.password_resets SET last_sent_at=? WHERE user_id=?", Instant.now().minusSeconds(61), id(email)); }
    private MvcResult forgot(String email) throws Exception { return post("/api/auth/forgot-password", "{\"email\":\"" + email + "\"}", 202); }
    private MvcResult reset(String email, String code, String password, Integer expected) throws Exception {
        return post("/api/auth/reset-password", "{\"email\":\"" + email + "\",\"code\":\"" + code + "\",\"password\":\"" + password + "\"}", expected);
    }
    private MvcResult login(String email, String password, int expected) throws Exception {
        return post("/api/auth/login", "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}", expected);
    }
    private MvcResult post(String path, String body, Integer expected) throws Exception {
        MvcResult csrf = mvc.perform(get("/api/auth/csrf")).andReturn();
        var action = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                .session(session(csrf)).header("X-CSRF-TOKEN", JsonPath.<String>read(csrf.getResponse().getContentAsString(), "$.token"))
                .contentType(MediaType.APPLICATION_JSON).content(body));
        if (expected != null) action.andExpect(status().is(expected));
        return action.andReturn();
    }
    private MockHttpSession session(MvcResult result) { return (MockHttpSession) result.getRequest().getSession(false); }
    private String lastCode(String email) {
        return mockingDetails(mailer).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("sendPasswordResetCode") && i.getArgument(0).equals(email))
                .reduce((first, last) -> last).orElseThrow().getArgument(1);
    }
    private long sendCount(String email) {
        return mockingDetails(mailer).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("sendPasswordResetCode") && i.getArgument(0).equals(email)).count();
    }
}
