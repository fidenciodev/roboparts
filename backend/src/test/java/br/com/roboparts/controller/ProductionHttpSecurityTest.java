package br.com.roboparts.controller;

import br.com.roboparts.support.BackendHttpTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Same real security chain, using only isolated H2 and mocked mail from BackendHttpTest.
@SpringBootTest(properties = {"app.security.production=true", "springdoc.api-docs.enabled=false", "springdoc.swagger-ui.enabled=false"})
class ProductionHttpSecurityTest extends BackendHttpTest {
    @Test void httpRedirectsBeforeCreatingASessionOrProcessingCredentials() throws Exception {
        var result = mvc.perform(get("http://roboparts.example/api/auth/csrf"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "https://roboparts.example/api/auth/csrf")).andReturn();
        assertThat(result.getRequest().getSession(false)).isNull();
    }

    @Test void httpsHasTransportAndContentProtections() throws Exception {
        mvc.perform(get("https://roboparts.example/api/auth/csrf").secure(true))
                .andExpect(status().isOk())
                .andExpect(header().string("Strict-Transport-Security", "max-age=31536000"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'"));
    }

    @Test void documentationIsBlockedForAnonymousAndAuthenticatedUsers() throws Exception {
        for (String path : java.util.List.of("/v3/api-docs", "/v3/api-docs.yaml", "/swagger-ui.html", "/swagger-ui/index.html")) {
            mvc.perform(get("https://roboparts.example" + path).secure(true)).andExpect(status().isUnauthorized());
            mvc.perform(get("https://roboparts.example" + path).secure(true).with(user("employee").roles("EMPLOYEE"))).andExpect(status().isForbidden());
        }
    }

    @Test void anonymousRecoveryStillRequiresCsrfButNeverRequiresALogin() throws Exception {
        mvc.perform(post("https://roboparts.example/api/auth/forgot-password").secure(true).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("https://roboparts.example/api/auth/forgot-password").secure(true).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("validation_error"));
    }

    @Test void anUnprocessedForwardedHeaderCannotBypassTheHttpsRequirement() throws Exception {
        mvc.perform(get("http://roboparts.example/api/auth/csrf").header("X-Forwarded-Proto", "https"))
                .andExpect(status().is3xxRedirection());
    }
}
