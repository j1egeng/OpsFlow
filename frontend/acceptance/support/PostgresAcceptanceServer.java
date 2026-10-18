import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.HashMap;
import java.util.Map;

import com.opsflow.OpsFlowApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.yaml.snakeyaml.Yaml;

/** Explicit opt-in acceptance harness. Never migrates or writes the configured opsflow database. */
class PostgresAcceptanceServer {
    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !args[1].matches("opsflow_acceptance_[a-f0-9]{12}"))
            throw new IllegalArgumentException("Expected an isolated acceptance database name");
        String mode = args[0], database = args[1], marker = "OpsFlow isolated acceptance: " + database;
        Map<?, ?> config;
        try (var reader = Files.newBufferedReader(Path.of("src/main/resources/application-local.yml"))) {
            config = new Yaml().load(reader);
        }
        Map<?, ?> datasource = (Map<?, ?>) ((Map<?, ?>) config.get("spring")).get("datasource");
        String original = (String) datasource.get("url");
        if (!original.matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):5432/opsflow(?:\\?.*)?"))
            throw new IllegalStateException("Acceptance requires the configured local opsflow database");
        String user = (String) datasource.get("username"), password = String.valueOf(datasource.get("password"));
        String target = original.substring(0, original.indexOf("/opsflow")) + "/" + database;
        try (Connection admin = DriverManager.getConnection(original, user, password)) {
            if (mode.equals("baseline")) {
                admin.setReadOnly(true);
                for (String table : new String[]{"users", "user_roles", "roles", "software_services", "incidents", "incident_notes",
                        "incident_status_history", "change_requests", "change_reviews", "change_status_history", "audit_logs", "flyway_schema_history"}) {
                    try (var statement = admin.createStatement(); var rows = statement.executeQuery("SELECT count(*) FROM public." + table)) {
                        rows.next(); System.out.println(table + "=" + rows.getLong(1));
                    }
                }
                return;
            }
            if (mode.equals("create")) {
                try (var query = admin.prepareStatement("SELECT 1 FROM pg_database WHERE datname=?")) {
                    query.setString(1, database);
                    try (var rows = query.executeQuery()) { if (rows.next()) throw new IllegalStateException("Database already exists; refusing to reuse it"); }
                }
                try (var statement = admin.createStatement()) {
                    statement.execute("CREATE DATABASE \"" + database + "\"");
                    statement.execute("COMMENT ON DATABASE \"" + database + "\" IS '" + marker + "'");
                }
                System.out.println("Created isolated database " + database);
                return;
            }
            try (var query = admin.prepareStatement("SELECT shobj_description(oid,'pg_database') FROM pg_database WHERE datname=?")) {
                query.setString(1, database);
                try (var rows = query.executeQuery()) {
                    if (!rows.next() || !marker.equals(rows.getString(1))) throw new IllegalStateException("Database ownership marker mismatch; refusing operation");
                }
            }
            if (mode.equals("drop")) {
                // No FORCE and no session termination: never kill connections belonging to another process.
                try (var statement = admin.createStatement()) { statement.execute("DROP DATABASE \"" + database + "\""); }
                System.out.println("Removed isolated database " + database);
                return;
            }
        }
        if (mode.equals("inspect")) {
            try (Connection connection = DriverManager.getConnection(target, user, password)) {
                connection.setReadOnly(true);
                try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT current_database(), version()")) {
                    rows.next(); System.out.println(rows.getString(1) + " | " + rows.getString(2));
                }
                for (String query : new String[]{"SELECT 'services=' || count(*) FROM software_services",
                        "SELECT 'incidents=' || count(*) FROM incidents", "SELECT 'changes=' || count(*) FROM change_requests",
                        "SELECT 'reviews=' || count(*) FROM change_reviews", "SELECT 'audit_logs=' || count(*) FROM audit_logs",
                        "SELECT 'incident:' || status || '=' || count(*) FROM incidents GROUP BY status ORDER BY status",
                        "SELECT 'change:' || status || '=' || count(*) FROM change_requests GROUP BY status ORDER BY status",
                        "SELECT 'failed_change_links=' || count(*) FROM incidents i JOIN change_requests c ON c.id=i.source_change_id AND c.service_id=i.service_id WHERE c.status='FAILED'"}) {
                    try (var statement = connection.createStatement(); var rows = statement.executeQuery(query)) {
                        while (rows.next()) System.out.println(rows.getString(1));
                    }
                }
            }
            return;
        }
        if (!mode.equals("serve")) throw new IllegalArgumentException("Unsupported acceptance operation");
        Map<String, Object> properties = new HashMap<>();
        properties.put("spring.datasource.url", target);
        properties.put("spring.datasource.username", user);
        properties.put("spring.datasource.password", password);
        properties.put("spring.datasource.driver-class-name", "org.postgresql.Driver");
        properties.put("spring.jpa.hibernate.ddl-auto", "validate");
        properties.put("spring.flyway.enabled", true);
        properties.put("server.port", 18082);
        properties.put("server.address", "127.0.0.1");
        properties.put("app.cors.allowed-origins", "http://127.0.0.1:4174");
        properties.put("app.bootstrap.admin.enabled", true);
        properties.put("app.bootstrap.admin.username", "acceptanceadmin");
        properties.put("app.bootstrap.admin.email", "acceptanceadmin@example.test");
        properties.put("app.bootstrap.admin.display-name", "TEST Administrator");
        properties.put("app.bootstrap.admin.password", "AcceptanceTestPass123!");
        properties.put("app.security.jwt.secret-base64", java.util.Base64.getEncoder().encodeToString(new java.security.SecureRandom().generateSeed(32)));
        SpringApplication application = new SpringApplication(OpsFlowApplication.class);
        var ready = new java.util.concurrent.atomic.AtomicBoolean(false);
        application.addInitializers(context -> {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("isolatedAcceptanceOnly", properties));
            context.getBeanFactory().registerSingleton("acceptanceReady", (org.springframework.boot.actuate.health.HealthIndicator)
                    () -> ready.get() ? org.springframework.boot.actuate.health.Health.up().build() : org.springframework.boot.actuate.health.Health.down().build());
        });
        application.addListeners(new org.springframework.context.ApplicationListener<org.springframework.boot.context.event.ApplicationReadyEvent>() {
            public void onApplicationEvent(org.springframework.boot.context.event.ApplicationReadyEvent event) { ready.set(true); }
        });
        application.run();
        System.out.println("ACCEPTANCE_POSTGRES_READY: " + database);
    }
}
