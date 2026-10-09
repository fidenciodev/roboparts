package br.com.roboparts.config;

import br.com.roboparts.service.DatabaseInspector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class DatabaseMigrationConfig {
    private static final Logger log = LoggerFactory.getLogger(DatabaseMigrationConfig.class);

    @Bean
    FlywayMigrationStrategy inspectedMigrationStrategy(DatabaseInspector inspector, Environment environment) {
        return flyway -> {
            String password = environment.getProperty("DB_PASSWORD");
            if (password == null || password.isBlank()) {
                throw new IllegalStateException("Defina DB_PASSWORD por variável de ambiente antes de iniciar o backend.");
            }
            DatabaseInspector.Inspection inspection = inspector.inspect();
            log.info("Inspeção anterior às migrações: banco={}, PostgreSQL={}, objetos existentes={}, histórico Flyway={}",
                    inspection.identity().name(), inspection.identity().serverVersion(),
                    inspection.objects().size(), inspection.historyExists());
            for (DatabaseInspector.ExistingObject object : inspection.objects()) {
                log.info("Objeto existente preservado: {}.{}", object.schema(), object.name());
            }
            inspector.validate(inspection);
            // migrate validates existing history (validate-on-migrate=true), accepts
            // pending migrations and creates the configured schema on first use.
            // A separate validate call rejects an application schema that is absent.
            flyway.migrate();
        };
    }
}
