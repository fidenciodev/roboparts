package br.com.roboparts.security;

import br.com.roboparts.service.DatabaseInspector;
import java.sql.*;
import java.util.Arrays;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Explicit offline cloud preparation; never starts a server or prints passwords. */
public final class CloudDatabaseMaintenance {
    private static final String ROLE="roboparts_web";
    private CloudDatabaseMaintenance() { }
    public static void main(String[] args) {
        try {
            if(args.length!=0)throw new IllegalArgumentException();
            String url=required("DB_URL"),owner=required("DB_USERNAME"),password=required("DB_PASSWORD");
            String appPassword=required("ROBOPARTS_APP_PASSWORD");
            prepare(new DriverManagerDataSource(url,owner,password),appPassword);
            var runtime=new DriverManagerDataSource(url,ROLE,appPassword);
            new DatabaseInspector(runtime).validateRuntimeRole();
            CloudSchema.validate(runtime);
            String pooled=System.getenv("ROBOPARTS_RUNTIME_DB_URL");
            if(pooled!=null&&!pooled.isBlank()) {
                var source=new DriverManagerDataSource(pooled,ROLE,appPassword);
                new DatabaseInspector(source).validateRuntimeRole();CloudSchema.validate(source);
            }
            System.out.println("Cloud schema and restricted runtime role verified.");
        } catch(Exception exception) {
            System.err.println("A preparacao do Neon falhou. Confira acesso, copia do RoboParts e permissoes; credenciais omitidas.");
            System.exit(1);
        }
    }

    public static void prepare(DataSource source,String password) throws Exception {
        if(!password.matches("[A-Za-z0-9_-]{43,128}"))throw new IllegalArgumentException();
        var inspector=new DatabaseInspector(source);inspector.validate(inspector.inspect());
        Flyway.configure().dataSource(source).defaultSchema("roboparts").schemas("roboparts")
                .locations("classpath:db/migration").cleanDisabled(true).baselineOnMigrate(false)
                .ignoreMigrationPatterns(new String[0]).load().validate();
        try(Connection connection=source.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try(Statement sql=connection.createStatement()){sql.execute("SET LOCAL lock_timeout='10s'");}
                CloudSchema.install(connection);
                try(Statement sql=connection.createStatement();ResultSet row=sql.executeQuery("""
                        SELECT rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls
                          OR EXISTS (SELECT 1 FROM pg_auth_members WHERE member=r.oid)
                          OR EXISTS (SELECT 1 FROM pg_database WHERE datdba=r.oid)
                          OR EXISTS (SELECT 1 FROM pg_namespace WHERE nspowner=r.oid)
                          OR EXISTS (SELECT 1 FROM pg_class WHERE relowner=r.oid)
                        FROM pg_roles r WHERE rolname='roboparts_web'
                        """)) {
                    if(row.next()) {if(row.getBoolean(1))throw new IllegalStateException("Existing runtime role is privileged.");}
                    else try(Statement create=connection.createStatement()){create.execute("CREATE ROLE roboparts_web NOLOGIN NOCREATEDB NOCREATEROLE NOINHERIT");}
                }
                try(Statement sql=connection.createStatement()) {
                    // Managed Postgres users need no superuser-only SET or role flags.
                    // The flags are already checked above (or default false at
                    // CREATE). Even restating NOCREATEDB in ALTER requires the
                    // owner itself to have CREATEDB on PostgreSQL 18.
                    sql.execute("ALTER ROLE roboparts_web LOGIN NOINHERIT");
                    sql.execute("REVOKE ALL ON DATABASE roboparts FROM roboparts_web");
                    sql.execute("GRANT CONNECT ON DATABASE roboparts TO roboparts_web");
                    sql.execute("REVOKE ALL ON SCHEMA roboparts FROM roboparts_web");
                    sql.execute("GRANT USAGE ON SCHEMA roboparts TO roboparts_web");
                    sql.execute("REVOKE ALL ON ALL TABLES IN SCHEMA roboparts FROM roboparts_web");
                    sql.execute("GRANT SELECT ON roboparts.application_metadata,roboparts.flyway_schema_history,roboparts.cloud_state TO roboparts_web");
                    sql.execute("GRANT SELECT,INSERT ON roboparts.audit_events,roboparts.movements TO roboparts_web");
                    sql.execute("GRANT SELECT,UPDATE ON roboparts.auth_attempt_locks TO roboparts_web");
                    sql.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON roboparts.users,roboparts.robots,roboparts.component_nodes,roboparts.checklists,roboparts.checklist_items,roboparts.email_verifications,roboparts.password_resets,roboparts.team_invitations,roboparts.security_events,roboparts.mfa_credentials,roboparts.mfa_recovery_codes,roboparts.web_sessions,roboparts.web_sessions_attributes,roboparts.auth_attempt_windows TO roboparts_web");
                }
                char[] chars=password.toCharArray();
                try {connection.unwrap(org.postgresql.PGConnection.class).alterUserPassword(ROLE,chars,"scram-sha-256");}
                finally {Arrays.fill(chars,'\0');}
                connection.commit();
            } catch(Exception exception) {connection.rollback();throw exception;}
        }
    }
    private static String required(String name) {
        String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalArgumentException();return value;
    }
}
