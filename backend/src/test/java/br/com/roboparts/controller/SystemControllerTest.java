package br.com.roboparts.controller;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.roboparts.dto.SystemStatusResponse;
import br.com.roboparts.support.BackendHttpTest;
import java.util.stream.Stream;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.test.context.support.WithMockUser;
class SystemControllerTest extends BackendHttpTest {

    @Test
    @WithMockUser(roles = "EMPLOYEE")
    void allowsAuthenticatedReadOfActualSystemStatus() throws Exception {
        when(service.getStatus()).thenReturn(healthyStatus());

        mvc.perform(get("/api/system/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.application").value("RoboParts"))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.database.version").value("18.1"))
                .andExpect(jsonPath("$.migrations.version").value("1"));
    }

    @Test
    void refusesAnonymousReadOfSystemStatus() throws Exception {
        mvc.perform(get("/api/system/status")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void refusesAnonymousAccessToUnimplementedPrivateRoutes() throws Exception {
        mvc.perform(get("/api/robots"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verifyNoInteractions(service);
    }

    @Test
    @WithMockUser(roles = "EMPLOYEE")
    void permitsAuthenticatedReadOfBusinessRoutes() throws Exception {
        mvc.perform(get("/api/robots")).andExpect(status().isOk());

        verifyNoInteractions(service);
    }

    @Test
    void doesNotAllowWritingToTheStatusEndpoint() throws Exception {
        mvc.perform(post("/api/system/status")).andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    @Test
    @WithMockUser(roles = "EMPLOYEE")
    void permitsTheConfiguredFrontendOrigin() throws Exception {
        when(service.getStatus()).thenReturn(healthyStatus());

        mvc.perform(get("/api/system/status").header(HttpHeaders.ORIGIN, "http://localhost:5173"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
    }

    @Test
    void handlesAllowedCorsPreflightWithoutCallingTheStatusService() throws Exception {
        mvc.perform(options("/api/system/status")
                        .header(HttpHeaders.ORIGIN, "http://127.0.0.1:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Content-Type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://127.0.0.1:5173"));

        verifyNoInteractions(service);
    }

    @Test
    void blocksAnUnconfiguredOriginBeforeReadingTheDatabase() throws Exception {
        mvc.perform(get("/api/system/status").header(HttpHeaders.ORIGIN, "https://untrusted.example"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));

        verifyNoInteractions(service);
    }

    @Test
    void permitsCorsPreflightForAuthenticatedApiWritesWithoutExposingStatusData() throws Exception {
        mvc.perform(options("/api/system/status")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Content-Type,X-CSRF-TOKEN"))
                .andExpect(status().isOk());

        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @MethodSource("infrastructureFailures")
    @WithMockUser(roles = "EMPLOYEE")
    void returns503WithoutExposingInternalFailureDetails(RuntimeException failure) throws Exception {
        when(service.getStatus()).thenThrow(failure);

        mvc.perform(get("/api/system/status"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.detail").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(content().string(not(containsString("internal-diagnostic-marker"))))
                .andExpect(content().string(not(containsString("jdbc:postgresql"))))
                .andExpect(jsonPath("$.stackTrace").doesNotExist());
    }

    private static Stream<RuntimeException> infrastructureFailures() {
        String detail = "internal-diagnostic-marker jdbc:postgresql://private-host/private-database";
        return Stream.of(new DataAccessResourceFailureException(detail),
                new FlywayException(detail), new IllegalStateException(detail));
    }

    private SystemStatusResponse healthyStatus() {
        return new SystemStatusResponse("RoboParts", "UP",
                new SystemStatusResponse.DatabaseStatus("UP", "18.1"),
                new SystemStatusResponse.MigrationStatus("1", "initial foundation"));
    }
}
