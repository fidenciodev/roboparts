package br.com.roboparts.support;

import br.com.roboparts.service.SystemStatusService;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Real servlet security and JPA against an isolated H2 fixture, never the local PostgreSQL. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class BackendHttpTest {
    @Autowired
    protected MockMvc mvc;

    // PostgreSQL's current_setting/Flyway inspection is covered separately, not imitated by H2.
    @MockitoBean
    protected SystemStatusService service;

    // Disabled auto-migrations must never connect to or mutate the user's PostgreSQL.
    @MockitoBean
    protected Flyway flyway;
}
