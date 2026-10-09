package br.com.roboparts.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.roboparts.security.SessionUser;
import br.com.roboparts.support.BackendHttpTest;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MvcResult;

class AuthHttpTest extends BackendHttpTest {
    private static final String ACCESS_CODE = "Test-only-invitation-code-2026-10";
    private static final String PASSWORD = "A-test-password-with-24-characters!";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void registersOnlyWithTheServerAccessCodeAndPersistsAHash() throws Exception {
        String email = uniqueEmail();
        CsrfSession csrf = csrf(null);

        MvcResult registration = mvc.perform(post("/api/auth/register").session(csrf.session())
                        .header("X-CSRF-TOKEN", csrf.token()).contentType(MediaType.APPLICATION_JSON)
                        .content(registrationBody("  Ana Silva  ", "  " + email.toUpperCase() + "  ",
                                PASSWORD, ACCESS_CODE)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.name").value("Ana Silva"))
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(content().string(not(containsString(PASSWORD))))
                .andExpect(content().string(not(containsString(ACCESS_CODE))))
                .andReturn();

        String hash = jdbc.queryForObject("SELECT password_hash FROM roboparts.users WHERE email = ?",
                String.class, email);
        assertThat(hash).isNotBlank().isNotEqualTo(PASSWORD);
        assertThat(passwordEncoder.matches(PASSWORD, hash)).isTrue();
        assertThat(passwordEncoder.matches("an-incorrect-password", hash)).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM roboparts.audit_events WHERE user_id = ? "
                        + "AND action = 'USER_REGISTERED'", Integer.class, UUID.fromString(id(registration))))
                .isEqualTo(1);
        // Registration does not grant a session: employees must authenticate explicitly.
        mvc.perform(get("/api/auth/me").session(csrf.session())).andExpect(status().isUnauthorized());
    }

    @Test
    void wrongInvitationCodeCannotCreateAnEmployee() throws Exception {
        String email = uniqueEmail();
        CsrfSession csrf = csrf(null);
        mvc.perform(post("/api/auth/register").session(csrf.session())
                        .header("X-CSRF-TOKEN", csrf.token()).contentType(MediaType.APPLICATION_JSON)
                        .content(registrationBody("Ana Silva", email, PASSWORD, "wrong-code")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("invalid_access_code"))
                .andExpect(content().string(not(containsString(ACCESS_CODE))));
        assertThat(userCount(email)).isZero();
    }

    @Test
    void missingInvitationCodeIsAValidationError() throws Exception {
        String email = uniqueEmail();
        CsrfSession csrf = csrf(null);
        mvc.perform(post("/api/auth/register").session(csrf.session())
                        .header("X-CSRF-TOKEN", csrf.token()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ana Silva\",\"email\":\"" + email
                                + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_error"));
        assertThat(userCount(email)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"name\":\"\",\"email\":\"valid@example.com\",\"password\":\"A-long-valid-password!\"}",
            "{\"name\":\"Ana Silva\",\"email\":\"not-an-email\",\"password\":\"A-long-valid-password!\"}",
            "{\"name\":\"Ana Silva\",\"email\":\"valid@example.com\",\"password\":\"short\"}"
    })
    void validatesRegistrationFieldsBeforePersisting(String fields) throws Exception {
        CsrfSession csrf = csrf(null);
        String body = fields.substring(0, fields.length() - 1) + ",\"accessCode\":\"" + ACCESS_CODE + "\"}";
        mvc.perform(post("/api/auth/register").session(csrf.session())
                        .header("X-CSRF-TOKEN", csrf.token()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_error"))
                .andExpect(jsonPath("$.errors").isNotEmpty());
    }

    @Test
    void rejectsTheSameEmailAfterWhitespaceAndCaseNormalization() throws Exception {
        String email = uniqueEmail();
        register(email);
        CsrfSession csrf = csrf(null);
        mvc.perform(post("/api/auth/register").session(csrf.session())
                        .header("X-CSRF-TOKEN", csrf.token()).contentType(MediaType.APPLICATION_JSON)
                        .content(registrationBody("Another Employee", "  " + email.toUpperCase() + "  ",
                                PASSWORD, ACCESS_CODE)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("email_already_registered"));
        assertThat(userCount(email)).isEqualTo(1);
    }

    @Test
    void concurrentRegistrationPreservesTheUniqueEmployeeAndAudit() throws Exception {
        String email = uniqueEmail();
        CsrfSession first = csrf(null);
        CsrfSession second = csrf(null);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var outcomes = List.of(first, second).stream().map(csrf -> executor.submit(() -> {
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return mvc.perform(post("/api/auth/register").session(csrf.session())
                                .header("X-CSRF-TOKEN", csrf.token()).contentType(MediaType.APPLICATION_JSON)
                                .content(registrationBody("Concurrent Employee", email, PASSWORD, ACCESS_CODE)))
                        .andReturn().getResponse().getStatus();
            })).toList();
            start.countDown();
            assertThat(List.of(outcomes.get(0).get(30, TimeUnit.SECONDS),
                    outcomes.get(1).get(30, TimeUnit.SECONDS))).containsExactlyInAnyOrder(201, 409);
        }
        assertThat(userCount(email)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM roboparts.audit_events a "
                        + "JOIN roboparts.users u ON u.id = a.user_id WHERE u.email = ? "
                        + "AND a.action = 'USER_REGISTERED'", Integer.class, email)).isEqualTo(1);
    }

    @Test
    void unknownEmailAndWrongPasswordHaveTheSameGenericFailure() throws Exception {
        String email = uniqueEmail();
        register(email);
        Map<String, Object> wrongPassword = failedLogin(email, "a-wrong-password-for-testing");
        Map<String, Object> absentEmployee = failedLogin(uniqueEmail(), PASSWORD);

        assertThat(wrongPassword).containsEntry("code", "invalid_credentials");
        for (String field : List.of("status", "title", "detail", "code")) {
            assertThat(wrongPassword.get(field)).as(field).isEqualTo(absentEmployee.get(field));
        }
    }

    @Test
    void anonymousClientsCannotReadProtectedIdentityHistoryOrStatus() throws Exception {
        for (String endpoint : List.of("/api/auth/me", "/api/auth/activities", "/api/system/status")) {
            mvc.perform(get(endpoint)).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("unauthenticated"));
        }
    }

    @Test
    void refusesMissingAndInvalidCsrfBeforeRegistrationAndLogin() throws Exception {
        String email = uniqueEmail();
        CsrfSession csrf = csrf(null);
        for (String endpoint : List.of("/api/auth/register", "/api/auth/login")) {
            String body = endpoint.endsWith("register")
                    ? registrationBody("Ana Silva", email, PASSWORD, ACCESS_CODE) : loginBody(email, PASSWORD);
            mvc.perform(post(endpoint).session(csrf.session()).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("csrf_invalid"));
            mvc.perform(post(endpoint).session(csrf.session()).header("X-CSRF-TOKEN", "invalid-token")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("csrf_invalid"));
        }
        assertThat(userCount(email)).isZero();
    }

    @Test
    void realLoginRotatesSessionPersistsIdentityAndReplacesTheCsrfToken() throws Exception {
        String email = uniqueEmail();
        String userId = id(register(email));
        CsrfSession anonymous = csrf(null);
        String oldSessionId = anonymous.session().getId();

        MvcResult loggedIn = login(anonymous, "  " + email.toUpperCase() + "  ", PASSWORD);
        MockHttpSession session = (MockHttpSession) loggedIn.getRequest().getSession(false);
        assertThat(session).isNotNull();
        assertThat(session.getId()).isNotEqualTo(oldSessionId);
        SecurityContext persisted = (SecurityContext) session.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(persisted).isNotNull();
        assertThat(persisted.getAuthentication().getPrincipal()).isInstanceOf(SessionUser.class);
        assertThat(persisted.getAuthentication().getCredentials()).isNull();
        String hash = jdbc.queryForObject("SELECT password_hash FROM roboparts.users WHERE email = ?",
                String.class, email);
        assertThat(loggedIn.getResponse().getContentAsString()).doesNotContain(PASSWORD, ACCESS_CODE, hash);

        for (int request = 0; request < 2; request++) {
            mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(userId)).andExpect(jsonPath("$.email").value(email))
                    .andExpect(jsonPath("$.password").doesNotExist())
                    .andExpect(jsonPath("$.passwordHash").doesNotExist());
        }
        mvc.perform(post("/api/auth/logout").session(session).header("X-CSRF-TOKEN", anonymous.token()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("csrf_invalid"));
        CsrfSession authenticated = csrf(session);
        mvc.perform(post("/api/auth/logout").session(session).header("X-CSRF-TOKEN", authenticated.token()))
                .andExpect(status().isNoContent());
        assertThat(session.isInvalid()).isTrue();
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());

        CsrfSession loggedOut = csrf(null);
        mvc.perform(post("/api/auth/login").session(loggedOut.session())
                        .header("X-CSRF-TOKEN", authenticated.token()).contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(email, PASSWORD)))
                .andExpect(status().isForbidden());
        login(loggedOut, email, PASSWORD);
        assertThat(jdbc.queryForList("SELECT action FROM roboparts.audit_events WHERE user_id = ?",
                String.class, UUID.fromString(userId)))
                .containsExactlyInAnyOrder("USER_REGISTERED", "USER_LOGGED_IN", "USER_LOGGED_OUT", "USER_LOGGED_IN");
    }

    @Test
    void activityIdentityComesFromTheSessionAndCannotBeSelectedByQuery() throws Exception {
        String ownEmail = uniqueEmail();
        String ownId = id(register(ownEmail));
        String otherId = id(register(uniqueEmail()));
        MockHttpSession session = (MockHttpSession) login(csrf(null), ownEmail, PASSWORD)
                .getRequest().getSession(false);
        MvcResult response = mvc.perform(get("/api/auth/activities").session(session)
                        .param("userId", otherId).param("limit", "20"))
                .andExpect(status().isOk()).andReturn();
        List<Map<String, Object>> activities = JsonPath.read(response.getResponse().getContentAsString(), "$");
        assertThat(activities).hasSize(2);
        assertThat(activities).allSatisfy(activity -> {
            assertThat(activity).containsKeys("id", "action", "createdAt");
            assertThat(activity).doesNotContainKeys("password", "passwordHash", "email", "accessCode");
            assertThat(jdbc.queryForObject("SELECT user_id FROM roboparts.audit_events WHERE id = ?",
                    UUID.class, UUID.fromString((String) activity.get("id")))).isEqualTo(UUID.fromString(ownId));
        });
        mvc.perform(get("/api/auth/activities").session(session).param("limit", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        for (String invalidLimit : List.of("0", "51", "invalid")) {
            mvc.perform(get("/api/auth/activities").session(session).param("limit", invalidLimit))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("validation_error"));
        }
    }

    @Test
    void auditRoutesHaveNoWriteOperationEvenForAnAuthenticatedEmployee() throws Exception {
        String email = uniqueEmail();
        register(email);
        MockHttpSession session = (MockHttpSession) login(csrf(null), email, PASSWORD)
                .getRequest().getSession(false);
        CsrfSession csrf = csrf(session);
        mvc.perform(post("/api/auth/activities").session(session).header("X-CSRF-TOKEN", csrf.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"USER_REGISTERED\"}"))
                .andExpect(status().isForbidden());
    }

    private MvcResult register(String email) throws Exception {
        CsrfSession csrf = csrf(null);
        return mvc.perform(post("/api/auth/register").session(csrf.session())
                        .header("X-CSRF-TOKEN", csrf.token()).contentType(MediaType.APPLICATION_JSON)
                        .content(registrationBody("Ana Silva", email, PASSWORD, ACCESS_CODE)))
                .andExpect(status().isCreated()).andReturn();
    }

    private MvcResult login(CsrfSession csrf, String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").session(csrf.session())
                        .header("X-CSRF-TOKEN", csrf.token()).contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(email, password)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value(email.trim().toLowerCase()))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist()).andReturn();
    }

    private Map<String, Object> failedLogin(String email, String password) throws Exception {
        CsrfSession csrf = csrf(null);
        MvcResult response = mvc.perform(post("/api/auth/login").session(csrf.session())
                        .header("X-CSRF-TOKEN", csrf.token()).contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(email, password)))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(not(containsString(password))))
                .andReturn();
        return JsonPath.read(response.getResponse().getContentAsString(), "$");
    }

    private CsrfSession csrf(MockHttpSession session) throws Exception {
        var request = get("/api/auth/csrf");
        if (session != null) request.session(session);
        MvcResult response = mvc.perform(request).andExpect(status().isOk())
                .andExpect(jsonPath("$.headerName").value("X-CSRF-TOKEN"))
                .andExpect(jsonPath("$.token").isNotEmpty()).andReturn();
        return new CsrfSession((MockHttpSession) response.getRequest().getSession(false),
                JsonPath.read(response.getResponse().getContentAsString(), "$.token"));
    }

    private String id(MvcResult response) throws Exception {
        return JsonPath.read(response.getResponse().getContentAsString(), "$.id");
    }

    private int userCount(String email) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM roboparts.users WHERE email = ?", Integer.class, email);
    }

    private static String uniqueEmail() { return "employee-" + UUID.randomUUID() + "@example.com"; }

    private static String registrationBody(String name, String email, String password, String code) {
        return "{\"name\":\"" + name + "\",\"email\":\"" + email + "\",\"password\":\""
                + password + "\",\"accessCode\":\"" + code + "\"}";
    }

    private static String loginBody(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    private record CsrfSession(MockHttpSession session, String token) { }
}
