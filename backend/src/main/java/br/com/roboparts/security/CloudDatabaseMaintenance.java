package br.com.roboparts.security;

import br.com.roboparts.service.DatabaseInspector;
import java.sql.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Explicit offline cloud preparation; never starts a server or prints passwords. */
public final class CloudDatabaseMaintenance {
    private static final String ROLE="roboparts_web";
    enum Stage { CONFIGURATION, OWNER_CONNECTION, CORE_SCHEMA, CLOUD_SCHEMA, ROLE_CHECK,
        ROLE_CREATE, ROLE_PASSWORD, ROLE_PRIVILEGES, COMMIT, DIRECT_RUNTIME, POOLED_RUNTIME, COMPLETE }
    private CloudDatabaseMaintenance() { }
    public static void main(String[] args) {
        var stage=new java.util.concurrent.atomic.AtomicReference<>(Stage.CONFIGURATION);
        try {
            if(args.length!=0)throw new IllegalArgumentException();
            String url=required("DB_URL"),owner=required("DB_USERNAME"),password=required("DB_PASSWORD");
            String appPassword=required("ROBOPARTS_APP_PASSWORD");
            prepare(new DriverManagerDataSource(url,owner,password),appPassword,stage::set);
            stage.set(Stage.DIRECT_RUNTIME);
            var runtime=new DriverManagerDataSource(url,ROLE,appPassword);
            new DatabaseInspector(runtime).validateRuntimeRole();
            CloudSchema.validate(runtime);
            String pooled=System.getenv("ROBOPARTS_RUNTIME_DB_URL");
            if(pooled!=null&&!pooled.isBlank()) {
                stage.set(Stage.POOLED_RUNTIME);
                var source=new DriverManagerDataSource(pooled,ROLE,appPassword);
                new DatabaseInspector(source).validateRuntimeRole();CloudSchema.validate(source);
            }
            stage.set(Stage.COMPLETE);
            writeDiagnostic(stage.get(),null);
            System.out.println("Cloud schema and restricted runtime role verified.");
        } catch(Exception exception) {
            writeDiagnostic(stage.get(),exception);
            System.err.println("A preparacao do Neon falhou. Confira acesso, copia do RoboParts e permissoes; credenciais omitidas.");
            System.exit(1);
        }
    }

    public static void prepare(DataSource source,String password) throws Exception {
        prepare(source,password,ignored->{});
    }
    private static void prepare(DataSource source,String password,Consumer<Stage> stage) throws Exception {
        if(!password.matches("[A-Za-z0-9_-]{43,128}"))throw new IllegalArgumentException();
        stage.accept(Stage.OWNER_CONNECTION);
        var inspector=new DatabaseInspector(source);inspector.validate(inspector.inspect());
        stage.accept(Stage.CORE_SCHEMA);
        Flyway.configure().dataSource(source).defaultSchema("roboparts").schemas("roboparts")
                .locations("classpath:db/migration").cleanDisabled(true).baselineOnMigrate(false)
                .ignoreMigrationPatterns(new String[0]).load().validate();
        try(Connection connection=source.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try(Statement sql=connection.createStatement()){sql.execute("SET LOCAL lock_timeout='10s'");}
                stage.accept(Stage.CLOUD_SCHEMA);
                CloudSchema.install(connection);
                stage.accept(Stage.ROLE_CHECK);
                try(Statement sql=connection.createStatement();ResultSet row=sql.executeQuery("""
                        SELECT rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls
                          OR EXISTS (SELECT 1 FROM pg_auth_members WHERE member=r.oid)
                          OR EXISTS (SELECT 1 FROM pg_database WHERE datdba=r.oid)
                          OR EXISTS (SELECT 1 FROM pg_namespace WHERE nspowner=r.oid)
                          OR EXISTS (SELECT 1 FROM pg_class WHERE relowner=r.oid)
                        FROM pg_roles r WHERE rolname='roboparts_web'
                        """)) {
                    if(row.next()) {if(row.getBoolean(1))throw new IllegalStateException("Existing runtime role is privileged.");}
                    else try(Statement create=connection.createStatement()){
                        stage.accept(Stage.ROLE_CREATE);
                        create.execute("CREATE ROLE roboparts_web NOLOGIN NOCREATEDB NOCREATEROLE NOINHERIT");
                    }
                }
                stage.accept(Stage.ROLE_PASSWORD);
                setRuntimePassword(connection,password);
                stage.accept(Stage.ROLE_PRIVILEGES);
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
                stage.accept(Stage.COMMIT);
                connection.commit();
            } catch(Exception exception) {connection.rollback();throw exception;}
        }
    }
    static void setRuntimePassword(Connection connection,String password) throws SQLException {
        // Neon rejects prehashed passwords. Send the generated URL-safe secret
        // through the verified TLS connection and let Postgres store SCRAM.
        // The allowlist is checked here as well as at the public entry point;
        // SQL role-management statements do not support bind parameters.
        if(password==null||!password.matches("[A-Za-z0-9_-]{43,128}"))throw new IllegalArgumentException();
        try(Statement sql=connection.createStatement()) {
            sql.execute("SET LOCAL password_encryption='scram-sha-256'");
            sql.execute("ALTER ROLE roboparts_web PASSWORD '"+password+"'");
        }
    }
    static String diagnostic(Stage stage,Throwable failure) {
        String category=failure==null?"SUCCESS":"VALIDATION",state="NONE";
        for(Throwable cause=failure;cause!=null;cause=cause.getCause()) {
            if(cause instanceof javax.net.ssl.SSLException) {category="TLS";break;}
            if(cause instanceof java.net.SocketException||cause instanceof java.net.UnknownHostException
                    ||cause instanceof java.net.SocketTimeoutException) {category="NETWORK";break;}
            if(cause instanceof SQLException sql) {
                category="SQL";
                if(sql.getSQLState()!=null&&sql.getSQLState().matches("[A-Z0-9]{5}"))state=sql.getSQLState();
            }
        }
        return "stage="+stage.name()+"\ncategory="+category+"\nsqlState="+state+"\n";
    }
    private static void writeDiagnostic(Stage stage,Throwable failure) {
        String path=System.getenv("ROBOPARTS_PREPARATION_DIAGNOSTIC");
        if(path==null||path.isBlank())return;
        try {Files.writeString(Path.of(path),diagnostic(stage,failure));}
        catch(Exception ignored) { /* Never include exception messages or credentials. */ }
    }
    private static String required(String name) {
        String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalArgumentException();return value;
    }
}
