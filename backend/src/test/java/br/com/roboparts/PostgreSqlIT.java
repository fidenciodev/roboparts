package br.com.roboparts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.roboparts.repository.ApplicationMetadataRepository;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opt-in, read-only verification against an already migrated local PostgreSQL database.
 * Does not migrate, create, clean, update or remove database objects or application data.
 */
@EnabledIfEnvironmentVariable(named = "ROBOPARTS_POSTGRES_IT", matches = "true")
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
@SpringBootTest(properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=validate"})
@AutoConfigureMockMvc
@Import(PostgreSqlIT.ReadOnlyFlywayConfiguration.class)
class PostgreSqlIT {
    @Autowired
    private MockMvc mvc;

    @Autowired
    private ApplicationMetadataRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @Transactional(readOnly = true)
    void readsThePersistedInstallationFromPostgreSql() {
        var metadata = repository.findById((short) 1).orElseThrow();

        assertThat(metadata.getId()).isEqualTo((short) 1);
        assertThat(metadata.getApplicationName()).isEqualTo("RoboParts");
        assertThat(metadata.getCreatedAt()).isNotNull();
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo("roboparts");
        assertThat(jdbc.queryForObject("SELECT current_setting('server_version_num')::integer", Integer.class))
                .isBetween(180000, 189999);
    }

    @Test
    @WithMockUser(roles = "EMPLOYEE")
    void verifiesTheWholeHttpToDatabaseReadPath() throws Exception {
        mvc.perform(get("/api/system/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.application").value("RoboParts"))
                .andExpect(jsonPath("$.database.status").value("UP"))
                .andExpect(jsonPath("$.migrations.version").value("3"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ReadOnlyFlywayConfiguration {
        @Bean
        Flyway readOnlyFlyway(DataSource dataSource) {
            return Flyway.configure().dataSource(dataSource).defaultSchema("roboparts")
                    .schemas("roboparts").createSchemas(false).cleanDisabled(true)
                    .baselineOnMigrate(false).load();
        }
    }
}
