package br.com.roboparts.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

class DatabaseInspectorTest {
    private final DatabaseInspector inspector = new DatabaseInspector(mock(JdbcTemplate.class));

    @ParameterizedTest
    @ValueSource(ints = {180000, 180001, 180099})
    void acceptsPostgreSql18WithAnEmptyApplicationSchema(int version) {
        assertThatCode(() -> inspector.validate(inspection(version, List.of(), false)))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 170000, 179999, 190000, 200000})
    void rejectsAnUnsupportedPostgreSqlVersion(int version) {
        assertThatThrownBy(() -> inspector.validate(inspection(version, List.of(), false)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void permitsExistingObjectsOutsideTheApplicationSchema() {
        var objects = List.of(new DatabaseInspector.ExistingObject("public", "existing_business_data"),
                new DatabaseInspector.ExistingObject("legacy", "report_view"));

        assertThatCode(() -> inspector.validate(inspection(180001, objects, false)))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsApplicationObjectsWithoutFlywayHistory() {
        var objects = List.of(new DatabaseInspector.ExistingObject("roboparts", "existing_component"));

        assertThatThrownBy(() -> inspector.validate(inspection(180001, objects, false)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsAccidentallyConnectingToAnotherDatabase() {
        var inspection = new DatabaseInspector.Inspection(
                new DatabaseInspector.DatabaseIdentity("unrelated_database", "18.1", 180001),
                List.of(), false);

        assertThatThrownBy(() -> inspector.validate(inspection)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsForeignFlywayHistoryAndApplicationObjectsWithoutTheInstallationMarker() {
        var objects = List.of(new DatabaseInspector.ExistingObject("roboparts", "flyway_schema_history"),
                new DatabaseInspector.ExistingObject("roboparts", "foreign_business_table"));

        assertThatThrownBy(() -> inspector.validate(inspection(180001, objects, true)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void acceptsApplicationObjectsWithFlywayHistoryForFurtherValidation() {
        var objects = List.of(new DatabaseInspector.ExistingObject("roboparts", "flyway_schema_history"),
                new DatabaseInspector.ExistingObject("roboparts", "application_metadata"));

        assertThatCode(() -> inspector.validate(inspection(180001, objects, true)))
                .doesNotThrowAnyException();
    }

    private DatabaseInspector.Inspection inspection(int version,
            List<DatabaseInspector.ExistingObject> objects, boolean historyExists) {
        return new DatabaseInspector.Inspection(
                new DatabaseInspector.DatabaseIdentity("roboparts", "18.1", version),
                objects, historyExists);
    }
}
