package br.com.roboparts.tools;

import br.com.roboparts.service.DatabaseInspector;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Read-only database inspection. Does not initialize Spring, JPA or Flyway. */
public final class InspectDatabase {
    private InspectDatabase() { }

    public static void main(String[] args) {
        String password = System.getenv("DB_PASSWORD");
        if (password == null || password.isBlank()) {
            System.err.println("DB_PASSWORD não definida. Nenhuma conexão ou alteração realizada.");
            System.exit(2);
        }
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("org.postgresql.Driver");
        source.setUrl(envOrDefault("DB_URL", "jdbc:postgresql://localhost:5432/roboparts"));
        source.setUsername(envOrDefault("DB_USERNAME", "postgres"));
        source.setPassword(password);
        source.setConnectionProperties(readOnlyProperties());
        try {
            DatabaseInspector inspector = new DatabaseInspector(new JdbcTemplate(source));
            DatabaseInspector.Inspection inspection = inspector.inspect();
            System.out.printf("Banco: %s; PostgreSQL: %s; histórico Flyway: %s%n",
                    inspection.identity().name(), inspection.identity().serverVersion(), inspection.historyExists());
            inspection.objects().forEach(object -> System.out.printf("  %s.%s%n", object.schema(), object.name()));
            inspector.validate(inspection);
            System.out.println("Inspeção concluída. Nenhuma migração foi executada.");
        } catch (Exception exception) {
            System.err.println("Falha na inspeção. Verifique conexão, credenciais, PostgreSQL 18 e conflitos no schema roboparts.");
            System.err.println("Tipo: " + exception.getClass().getSimpleName());
            System.exit(1);
        }
    }

    private static java.util.Properties readOnlyProperties() {
        java.util.Properties properties = new java.util.Properties();
        properties.setProperty("options", "-c default_transaction_read_only=on");
        properties.setProperty("connectTimeout", "10");
        properties.setProperty("socketTimeout", "10");
        return properties;
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
