package br.com.roboparts.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.com.roboparts.service.DatabaseInspector;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

class DatabaseMigrationConfigTest {
    private final DatabaseInspector inspector = mock(DatabaseInspector.class);
    private final Flyway flyway = mock(Flyway.class);
    private final DatabaseMigrationConfig configuration = new DatabaseMigrationConfig();

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "\t\n"})
    void requiresPasswordBeforeAccessingTheDatabase(String password) {
        var environment = new MockEnvironment();
        if (password != null) {
            environment.setProperty("DB_PASSWORD", password);
        }
        var strategy = configuration.inspectedMigrationStrategy(inspector, environment);

        assertThatThrownBy(() -> strategy.migrate(flyway))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("DB_PASSWORD");
        verifyNoInteractions(inspector, flyway);
    }

    @Test
    void inspectsAndValidatesTheDatabaseBeforeMigrating() {
        var inspection = emptyInspection();
        when(inspector.inspect()).thenReturn(inspection);
        var strategy = configuration.inspectedMigrationStrategy(inspector,
                new MockEnvironment().withProperty("DB_PASSWORD", "unit-test-only"));

        strategy.migrate(flyway);

        var ordered = inOrder(inspector, flyway);
        ordered.verify(inspector).inspect();
        ordered.verify(inspector).validate(inspection);
        ordered.verify(flyway).migrate();
    }

    @Test
    void neverMigratesWhenExistingObjectsConflict() {
        var inspection = emptyInspection();
        when(inspector.inspect()).thenReturn(inspection);
        doThrow(new IllegalStateException("Schema conflict"))
                .when(inspector).validate(inspection);
        var strategy = configuration.inspectedMigrationStrategy(inspector,
                new MockEnvironment().withProperty("DB_PASSWORD", "unit-test-only"));

        assertThatThrownBy(() -> strategy.migrate(flyway)).isInstanceOf(IllegalStateException.class);

        verify(flyway, never()).migrate();
    }

    @Test
    void neverMigratesWhenInspectionCannotConnect() {
        when(inspector.inspect()).thenThrow(new IllegalStateException("Connection unavailable"));
        var strategy = configuration.inspectedMigrationStrategy(inspector,
                new MockEnvironment().withProperty("DB_PASSWORD", "unit-test-only"));

        assertThatThrownBy(() -> strategy.migrate(flyway)).isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(flyway);
    }

    @Test
    void propagatesFlywayHistoryOrChecksumValidationFailures() {
        when(inspector.inspect()).thenReturn(emptyInspection());
        when(flyway.migrate()).thenThrow(new FlywayException("Invalid migration checksum"));
        var strategy = configuration.inspectedMigrationStrategy(inspector,
                new MockEnvironment().withProperty("DB_PASSWORD", "unit-test-only"));

        assertThatThrownBy(() -> strategy.migrate(flyway)).isInstanceOf(FlywayException.class);

        verify(flyway).migrate();
    }

    @Test
    void initializesAnAbsentSchemaAndAppliesLaterPendingMigrations(@TempDir Path migrations) throws Exception {
        Files.writeString(migrations.resolve("V1__initial.sql"), "CREATE TABLE \"roboparts\".initial_data (id INTEGER);");
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        var realFlyway = testFlyway(url, migrations);
        when(inspector.inspect()).thenReturn(emptyInspection());
        var strategy = configuration.inspectedMigrationStrategy(inspector,
                new MockEnvironment().withProperty("DB_PASSWORD", "unit-test-only"));

        strategy.migrate(realFlyway);
        assertThat(realFlyway.info().current().getVersion().getVersion()).isEqualTo("1");

        Files.writeString(migrations.resolve("V2__next.sql"), "CREATE TABLE \"roboparts\".next_data (id INTEGER);");
        when(inspector.inspect()).thenReturn(installedInspection());
        strategy.migrate(testFlyway(url, migrations));
        assertThat(testFlyway(url, migrations).info().current().getVersion().getVersion()).isEqualTo("2");
        try (var connection = DriverManager.getConnection(url, "sa", "");
                var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT COUNT(*) FROM \"roboparts\".next_data")) {
            assertThat(rows.next()).isTrue();
        }
    }

    @Test
    void rejectsChangedChecksumsBeforeApplyingPendingMigrations(@TempDir Path migrations) throws Exception {
        Path firstMigration = migrations.resolve("V1__initial.sql");
        Files.writeString(firstMigration, "CREATE TABLE \"roboparts\".initial_data (id INTEGER);");
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        when(inspector.inspect()).thenReturn(emptyInspection());
        var strategy = configuration.inspectedMigrationStrategy(inspector,
                new MockEnvironment().withProperty("DB_PASSWORD", "unit-test-only"));
        strategy.migrate(testFlyway(url, migrations));

        Files.writeString(firstMigration, "CREATE TABLE \"roboparts\".initial_data (id BIGINT);");
        Files.writeString(migrations.resolve("V2__next.sql"), "CREATE TABLE \"roboparts\".next_data (id INTEGER);");
        when(inspector.inspect()).thenReturn(installedInspection());

        assertThatThrownBy(() -> strategy.migrate(testFlyway(url, migrations)))
                .isInstanceOf(FlywayException.class).hasMessageContaining("checksum");
        try (var connection = DriverManager.getConnection(url, "sa", "");
                var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'roboparts' AND table_name = 'NEXT_DATA'")) {
            rows.next();
            assertThat(rows.getInt(1)).isZero();
        }
    }

    private Flyway testFlyway(String url, Path migrations) {
        return Flyway.configure().dataSource(url, "sa", "")
                .locations("filesystem:" + migrations.toAbsolutePath())
                .defaultSchema("roboparts").schemas("roboparts").createSchemas(true)
                .validateOnMigrate(true).ignoreMigrationPatterns(new String[0])
                .cleanDisabled(true).baselineOnMigrate(false).load();
    }

    private DatabaseInspector.Inspection installedInspection() {
        return new DatabaseInspector.Inspection(
                new DatabaseInspector.DatabaseIdentity("roboparts", "18.1", 180001),
                List.of(new DatabaseInspector.ExistingObject("roboparts", "flyway_schema_history"),
                        new DatabaseInspector.ExistingObject("roboparts", "application_metadata")), true);
    }

    private DatabaseInspector.Inspection emptyInspection() {
        return new DatabaseInspector.Inspection(
                new DatabaseInspector.DatabaseIdentity("roboparts", "18.1", 180001), List.of(), false);
    }
}
