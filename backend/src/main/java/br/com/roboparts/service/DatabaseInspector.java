package br.com.roboparts.service;

import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DatabaseInspector {
    public static final String SCHEMA = "roboparts";
    private final JdbcTemplate jdbc;

    @Autowired
    public DatabaseInspector(DataSource dataSource) { this.jdbc = new JdbcTemplate(dataSource); }

    public DatabaseInspector(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Inspection inspect() {
        DatabaseIdentity identity = jdbc.queryForObject("""
                SELECT current_database() AS database_name,
                       current_setting('server_version') AS server_version,
                       current_setting('server_version_num')::integer AS version_number
                """, (rs, row) -> new DatabaseIdentity(rs.getString("database_name"),
                        rs.getString("server_version"), rs.getInt("version_number")));
        List<ExistingObject> objects = jdbc.query("""
                SELECT n.nspname AS schema_name, c.relname AS object_name
                FROM pg_catalog.pg_class c
                JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname NOT IN ('information_schema', 'pg_catalog')
                  AND n.nspname NOT LIKE 'pg_toast%' AND n.nspname NOT LIKE 'pg_temp%'
                  AND c.relkind IN ('r', 'p', 'v', 'm', 'S', 'f')
                UNION ALL
                SELECT n.nspname, p.proname
                FROM pg_catalog.pg_proc p
                JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
                WHERE n.nspname NOT IN ('information_schema', 'pg_catalog')
                  AND n.nspname NOT LIKE 'pg_toast%' AND n.nspname NOT LIKE 'pg_temp%'
                UNION ALL
                SELECT n.nspname, t.typname
                FROM pg_catalog.pg_type t
                JOIN pg_catalog.pg_namespace n ON n.oid = t.typnamespace
                WHERE n.nspname NOT IN ('information_schema', 'pg_catalog')
                  AND n.nspname NOT LIKE 'pg_toast%' AND n.nspname NOT LIKE 'pg_temp%'
                  AND (t.typtype IN ('e', 'd') OR (t.typtype = 'c' AND EXISTS
                    (SELECT 1 FROM pg_catalog.pg_class c WHERE c.oid = t.typrelid AND c.relkind = 'c')))
                ORDER BY schema_name, object_name
                """, (rs, row) -> new ExistingObject(rs.getString("schema_name"), rs.getString("object_name")));
        Boolean history = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM information_schema.tables
                  WHERE table_schema = 'roboparts' AND table_name = 'flyway_schema_history'
                    AND table_type = 'BASE TABLE')
                """, Boolean.class);
        return new Inspection(identity, objects, Boolean.TRUE.equals(history));
    }

    public void validate(Inspection inspection) {
        if (!"roboparts".equals(inspection.identity().name())) {
            throw new IllegalStateException("Conecte ao banco existente roboparts. Nenhuma migração foi executada.");
        }
        int version = inspection.identity().versionNumber();
        if (version < 180000 || version >= 190000) {
            throw new IllegalStateException("RoboParts exige PostgreSQL 18. Nenhuma migração foi executada.");
        }
        boolean hasOwnedObjects = inspection.objects().stream().anyMatch(object -> SCHEMA.equals(object.schema()));
        if (hasOwnedObjects && !inspection.historyExists()) {
            throw new IllegalStateException("O schema roboparts contém objetos sem histórico Flyway. "
                    + "Inspecione e concilie o schema antes de iniciar; nenhum baseline automático será feito.");
        }
        if (inspection.historyExists()) {
            List<String> owned = inspection.objects().stream().filter(object -> SCHEMA.equals(object.schema()))
                    .map(ExistingObject::name).toList();
            if (owned.stream().anyMatch(name -> !"flyway_schema_history".equals(name))
                    && !owned.contains("application_metadata")) {
                throw new IllegalStateException("O histórico existente não identifica uma instalação RoboParts. "
                        + "Nenhuma migração foi executada.");
            }
        }
    }

    public record DatabaseIdentity(String name, String serverVersion, int versionNumber) { }
    public record ExistingObject(String schema, String name) { }
    public record Inspection(DatabaseIdentity identity, List<ExistingObject> objects, boolean historyExists) { }
}
