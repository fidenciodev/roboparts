package br.com.roboparts.service;

import br.com.roboparts.dto.SystemStatusResponse;
import br.com.roboparts.dto.SystemStatusResponse.DatabaseStatus;
import br.com.roboparts.dto.SystemStatusResponse.MigrationStatus;
import br.com.roboparts.entity.ApplicationMetadata;
import br.com.roboparts.repository.ApplicationMetadataRepository;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SystemStatusService {
    private final ApplicationMetadataRepository repository;
    private final JdbcTemplate jdbc;
    private final Flyway flyway;

    public SystemStatusService(ApplicationMetadataRepository repository, JdbcTemplate jdbc, Flyway flyway) {
        this.repository = repository;
        this.jdbc = jdbc;
        this.flyway = flyway;
    }

    @Transactional(readOnly = true)
    public SystemStatusResponse getStatus() {
        ApplicationMetadata metadata = repository.findById((short) 1)
                .orElseThrow(() -> new IllegalStateException("Metadados da aplicação ausentes."));
        String version = jdbc.queryForObject("SELECT current_setting('server_version')", String.class);
        MigrationInfo current = flyway.info().current();
        if (current == null || current.getVersion() == null) {
            throw new IllegalStateException("Fundação do banco não inicializada.");
        }
        return new SystemStatusResponse(metadata.getApplicationName(), "UP",
                new DatabaseStatus("UP", version),
                new MigrationStatus(current.getVersion().getVersion(), current.getDescription()));
    }
}
