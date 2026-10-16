package br.com.roboparts.config;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import static org.assertj.core.api.Assertions.*;

class EmailVerificationMigrationTest {
    @Test void migrationPreservesExistingAccountsAndRequiresConfirmationWithoutAssumingOwnership() throws Exception {
        var source=new DriverManagerDataSource("jdbc:h2:mem:email-migration-"+UUID.randomUUID()+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        var jdbc=new JdbcTemplate(source);jdbc.execute("CREATE SCHEMA roboparts");
        jdbc.execute("CREATE TABLE roboparts.users(id UUID PRIMARY KEY, name VARCHAR(100), email VARCHAR(254), password_hash VARCHAR(255))");
        UUID id=UUID.randomUUID();jdbc.update("INSERT INTO roboparts.users VALUES (?,?,?,?)",id,"Ana Silva","ana@example.com","preserved-test-hash");
        try(var connection=source.getConnection()) {
            ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration/V4__email_verification.sql"));
        }
        assertThat(jdbc.queryForObject("SELECT email_verified FROM roboparts.users WHERE id=?",Boolean.class,id)).isFalse();
        assertThat(jdbc.queryForObject("SELECT password_hash FROM roboparts.users WHERE id=?",String.class,id)).isEqualTo("preserved-test-hash");
        assertThat(jdbc.queryForObject("SELECT name FROM roboparts.users WHERE id=?",String.class,id)).isEqualTo("Ana Silva");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM roboparts.email_verifications",Integer.class)).isZero();
    }
}
