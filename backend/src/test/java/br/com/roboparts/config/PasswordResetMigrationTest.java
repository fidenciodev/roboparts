package br.com.roboparts.config;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import static org.assertj.core.api.Assertions.*;

class PasswordResetMigrationTest {
    @Test void addsRecoveryWithoutChangingExistingPasswordsOrProfiles() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:reset-migration-" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE SCHEMA roboparts");
        jdbc.execute("CREATE TABLE roboparts.users(id UUID PRIMARY KEY, email VARCHAR(254), password_hash VARCHAR(255))");
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO roboparts.users VALUES (?,?,?)", id, "ana@example.com", "existing-hash");
        try (var connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V5__password_reset.sql"));
        }
        assertThat(jdbc.queryForObject("SELECT password_hash FROM roboparts.users WHERE id=?", String.class, id)).isEqualTo("existing-hash");
        assertThat(jdbc.queryForObject("SELECT password_version FROM roboparts.users WHERE id=?", Long.class, id)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM roboparts.password_resets", Integer.class)).isZero();
    }
}
