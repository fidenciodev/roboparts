package br.com.roboparts.security;

import br.com.roboparts.service.DatabaseInspector;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Offline maintenance entry point. Never starts the web application or prints credentials. */
public final class DatabaseMaintenance {
    private DatabaseMaintenance() { }
    public static void main(String[] args) {
        try {
            if(args.length!=1||!Set.of("migrate","roles").contains(args[0]))throw new IllegalArgumentException();
            String url=System.getenv().getOrDefault("DB_URL","jdbc:postgresql://localhost:5432/roboparts");
            String user=System.getenv().getOrDefault("DB_USERNAME","postgres"), password=required("DB_PASSWORD");
            var source=new DriverManagerDataSource(url,user,password);
            var inspector=new DatabaseInspector(source);inspector.validate(inspector.inspect());
            if(args[0].equals("migrate")) {
                Flyway.configure().dataSource(source).defaultSchema("roboparts").schemas("roboparts")
                        .locations("classpath:db/migration").cleanDisabled(true).baselineOnMigrate(false)
                        .validateMigrationNaming(true).ignoreMigrationPatterns(new String[0]).validateOnMigrate(true).load().migrate();
                System.out.println("Migrações verificadas e aplicadas no banco roboparts.");
            } else {
                try(Connection connection=source.getConnection()) {
                    connection.setAutoCommit(false);
                    try {
                        try(Statement sql=connection.createStatement()) {
                            sql.execute("SET LOCAL log_statement='none'");
                            sql.execute("SET LOCAL log_min_error_statement='panic'");
                            sql.execute("SET LOCAL lock_timeout='10s'");
                        }
                        configureRole(connection,"roboparts_app",required("ROBOPARTS_APP_PASSWORD"),false);
                        configureRole(connection,"roboparts_backup",required("ROBOPARTS_BACKUP_PASSWORD"),true);
                        connection.commit();
                    } catch(Exception exception) { connection.rollback();throw exception; }
                }
                System.out.println("Usuários exclusivos configurados; dados e conta postgres preservados.");
            }
        } catch(Exception exception) {
            System.err.println("A manutenção não foi concluída. Confira acesso, versão e configuração do banco; credenciais e detalhes internos omitidos.");
            System.exit(1);
        }
    }
    private static String required(String name) {
        String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalArgumentException();return value;
    }
    private static void configureRole(Connection connection,String role,String password,boolean readOnly) throws SQLException {
        if(!password.matches("[A-Za-z0-9_-]{43,128}"))throw new IllegalArgumentException();
        try(PreparedStatement existing=connection.prepareStatement("SELECT rolsuper,rolcreatedb,rolcreaterole,rolreplication,rolbypassrls FROM pg_roles WHERE rolname=?")) {
            existing.setString(1,role);
            try(ResultSet row=existing.executeQuery()) {
                if(row.next()) { for(int i=1;i<=5;i++)if(row.getBoolean(i))throw new IllegalStateException(); }
                else try(Statement sql=connection.createStatement()) { sql.execute("CREATE ROLE "+role+" NOLOGIN"); }
            }
        }
        try(Statement sql=connection.createStatement()) {
            sql.execute("ALTER ROLE "+role+" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
            sql.execute("GRANT CONNECT ON DATABASE roboparts TO "+role);
            sql.execute("GRANT USAGE ON SCHEMA roboparts TO "+role);
            sql.execute("REVOKE ALL ON ALL TABLES IN SCHEMA roboparts FROM "+role);
            if(readOnly)sql.execute("GRANT SELECT ON ALL TABLES IN SCHEMA roboparts TO "+role);
            else {
                sql.execute("GRANT SELECT ON roboparts.application_metadata,roboparts.flyway_schema_history TO "+role);
                sql.execute("GRANT SELECT,INSERT ON roboparts.audit_events,roboparts.movements TO "+role);
                sql.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON roboparts.users,roboparts.robots,roboparts.component_nodes,roboparts.checklists,roboparts.checklist_items,roboparts.email_verifications,roboparts.password_resets,roboparts.team_invitations,roboparts.security_events,roboparts.mfa_credentials,roboparts.mfa_recovery_codes TO "+role);
            }
        }
        // The official driver sends a SCRAM verifier, keeping plaintext out of SQL logs.
        connection.unwrap(org.postgresql.PGConnection.class).alterUserPassword(role,password.toCharArray(),"scram-sha-256");
    }
}
