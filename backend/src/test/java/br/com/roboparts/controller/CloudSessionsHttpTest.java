package br.com.roboparts.controller;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import br.com.roboparts.security.SessionUser;
import br.com.roboparts.support.BackendHttpTest;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@ActiveProfiles({"test","prod","vercel"})
@TestPropertySource(properties={
    "spring.datasource.url=jdbc:h2:mem:cloud-http;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.sql.init.schema-locations=classpath:db/test-schema.sql,classpath:db/cloud/schema-v1.sql",
    "app.cors.allowed-origins=https://roboparts.example",
    "app.registration.require-invitation=true",
    "debug=false", "trace=false", "logging.level.root=INFO", "logging.level.org.springframework=INFO"
})
class CloudSessionsHttpTest extends BackendHttpTest {
    static final String PASSWORD="A-long-test-password-for-cloud!";
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcIndexedSessionRepository sessions;

    private String employee() {
        String email=UUID.randomUUID()+"@example.com";
        jdbc.update("INSERT INTO roboparts.users(id,name,email,password_hash,created_at,email_verified) VALUES (?,?,?,?,?,true)",
                UUID.randomUUID(),"Cloud fixture",email,passwords.encode(PASSWORD),Instant.now());
        return email;
    }
    private Cookie cookie(MvcResult result) {
        String value=result.getResponse().getHeaders("Set-Cookie").stream().filter(header->header.startsWith("JSESSIONID=")).findFirst().orElseThrow();
        return new Cookie("JSESSIONID",value.substring("JSESSIONID=".length(),value.indexOf(';')));
    }
    private MvcResult csrf(Cookie cookie) throws Exception {
        var request=get("https://roboparts.example/api/auth/csrf").secure(true);if(cookie!=null)request.cookie(cookie);
        return mvc.perform(request).andExpect(status().isOk()).andReturn();
    }
    private MvcResult login(String email,Cookie cookie,String token) throws Exception {
        return mvc.perform(post("https://roboparts.example/api/auth/login").secure(true).cookie(cookie).header("X-CSRF-TOKEN",token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\""+email+"\",\"password\":\""+PASSWORD+"\"}"))
                .andExpect(status().isOk()).andReturn();
    }
    private Cookie login(String email) throws Exception {
        var csrf=csrf(null);return cookie(login(email,cookie(csrf),JsonPath.read(csrf.getResponse().getContentAsString(),"$.token")));
    }
    @Test void loginRotatesSessionAndAnotherRepositoryCanReadThePersistedIdentityAndCsrf() throws Exception {
        String email=employee();var anonymous=csrf(null);Cookie old=cookie(anonymous);
        var result=login(email,old,JsonPath.read(anonymous.getResponse().getContentAsString(),"$.token"));
        Cookie authenticated=cookie(result);
        assertThat(authenticated.getValue()).isNotEqualTo(old.getValue());
        assertThat(result.getResponse().getHeaders("Set-Cookie").toString()).contains("Secure","HttpOnly","SameSite=Lax");
        var restarted=new JdbcIndexedSessionRepository(jdbc,new TransactionTemplate(manager));
        restarted.setTableName("roboparts.web_sessions");
        org.springframework.session.Session stored=restarted.findById(authenticated.getValue());
        assertThat(stored).isNotNull();
        SecurityContext context=stored.getAttribute("SPRING_SECURITY_CONTEXT");
        assertThat(context.getAuthentication().getPrincipal()).isInstanceOf(SessionUser.class);
        assertThat(((SessionUser)context.getAuthentication().getPrincipal()).email()).isEqualTo(email);
        assertThat(context.getAuthentication().getCredentials()).isNull();
        mvc.perform(get("https://roboparts.example/api/auth/me").secure(true).cookie(authenticated)).andExpect(status().isOk()).andExpect(jsonPath("$.email").value(email));
        mvc.perform(get("https://roboparts.example/api/auth/me").secure(true).cookie(old)).andExpect(status().isUnauthorized());
        assertThat(csrf(authenticated).getResponse().getContentAsString()).contains("token");
    }
    @Test void passwordVersionChangeRevokesPersistedSessionAndLogoutDeletesIt() throws Exception {
        String email=employee();Cookie revoked=login(email);
        jdbc.update("UPDATE roboparts.users SET password_version=password_version+1 WHERE email=?",email);
        mvc.perform(get("https://roboparts.example/api/auth/me").secure(true).cookie(revoked)).andExpect(status().isUnauthorized());
        Cookie current=login(email);var token=csrf(current);
        mvc.perform(post("https://roboparts.example/api/auth/logout").secure(true).cookie(current)
                .header("X-CSRF-TOKEN",JsonPath.<String>read(token.getResponse().getContentAsString(),"$.token")))
                .andExpect(status().isNoContent());
        assertThat(sessions.findById(current.getValue())).isNull();
        mvc.perform(get("https://roboparts.example/api/auth/me").secure(true).cookie(current)).andExpect(status().isUnauthorized());
    }
    @Test void expiredDatabaseSessionIsRejected() throws Exception {
        Cookie cookie=login(employee());long expired=Instant.now().minus(Duration.ofHours(2)).toEpochMilli();
        jdbc.update("UPDATE roboparts.web_sessions SET last_access_time=?,expiry_time=? WHERE session_id=?",expired,expired,cookie.getValue());
        mvc.perform(get("https://roboparts.example/api/auth/me").secure(true).cookie(cookie)).andExpect(status().isUnauthorized());
    }
}
