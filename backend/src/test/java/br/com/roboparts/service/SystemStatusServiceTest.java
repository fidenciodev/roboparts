package br.com.roboparts.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.com.roboparts.entity.ApplicationMetadata;
import br.com.roboparts.repository.ApplicationMetadataRepository;
import java.util.Optional;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

class SystemStatusServiceTest {
    private final ApplicationMetadataRepository repository = mock(ApplicationMetadataRepository.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final Flyway flyway = mock(Flyway.class);
    private final MigrationInfoService information = mock(MigrationInfoService.class);
    private final SystemStatusService service = new SystemStatusService(repository, jdbc, flyway);

    @BeforeEach
    void configureStoredMetadata() {
        var metadata = mock(ApplicationMetadata.class);
        when(metadata.getApplicationName()).thenReturn("RoboParts");
        when(repository.findById((short) 1)).thenReturn(Optional.of(metadata));
    }

    @Test
    void readsPersistedMetadataPostgreSqlVersionAndFlywayVersion() {
        when(jdbc.queryForObject("SELECT current_setting('server_version')", String.class)).thenReturn("18.1");
        when(flyway.info()).thenReturn(information);
        var migration = mock(MigrationInfo.class);
        when(information.current()).thenReturn(migration);
        when(migration.getVersion()).thenReturn(MigrationVersion.fromVersion("1"));
        when(migration.getDescription()).thenReturn("initial foundation");

        var status = service.getStatus();

        assertThat(status.application()).isEqualTo("RoboParts");
        assertThat(status.status()).isEqualTo("UP");
        assertThat(status.database().status()).isEqualTo("UP");
        assertThat(status.database().version()).isEqualTo("18.1");
        assertThat(status.migrations().version()).isEqualTo("1");
        assertThat(status.migrations().description()).isEqualTo("initial foundation");
    }

    @Test
    void neverReportsReadyWhenStoredMetadataIsMissing() {
        when(repository.findById((short) 1)).thenReturn(Optional.empty());

        assertThatThrownBy(service::getStatus).isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(jdbc, flyway);
    }

    @Test
    void neverReportsReadyWhenNoMigrationHasBeenApplied() {
        when(jdbc.queryForObject("SELECT current_setting('server_version')", String.class)).thenReturn("18.1");
        when(flyway.info()).thenReturn(information);
        when(information.current()).thenReturn(null);

        assertThatThrownBy(service::getStatus).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void neverReportsReadyForAnUnversionedMigration() {
        when(jdbc.queryForObject("SELECT current_setting('server_version')", String.class)).thenReturn("18.1");
        when(flyway.info()).thenReturn(information);
        when(information.current()).thenReturn(mock(MigrationInfo.class));

        assertThatThrownBy(service::getStatus).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void propagatesDatabaseFailureInsteadOfInventingAHealthyResult() {
        var failure = new DataAccessResourceFailureException("Connection unavailable");
        when(jdbc.queryForObject("SELECT current_setting('server_version')", String.class)).thenThrow(failure);

        assertThatThrownBy(service::getStatus).isSameAs(failure);

        verifyNoInteractions(flyway);
    }
}
