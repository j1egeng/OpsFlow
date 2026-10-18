package com.opsflow;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** Destructive fixture reset is intentionally limited to disposable, in-memory H2. */
final class H2TestData {
    private H2TestData() { }
    static void reset(JdbcTemplate jdbc) {
        try (var connection = jdbc.getDataSource().getConnection()) {
            if (!connection.getMetaData().getURL().startsWith("jdbc:h2:mem:")) throw new IllegalStateException("Fixture reset requires in-memory H2");
        } catch (java.sql.SQLException error) { throw new IllegalStateException(error); }
        jdbc.update("UPDATE incidents SET source_change_id=NULL");
        jdbc.update("UPDATE change_requests SET related_incident_id=NULL");
        for (String table : List.of("audit_logs", "change_reviews", "change_status_history", "change_requests", "incident_notes", "incident_status_history", "incidents", "software_services", "user_roles", "users", "roles"))
            jdbc.update("DELETE FROM " + table);
    }
}
