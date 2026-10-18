package com.opsflow;

import java.sql.Connection;
import java.sql.Savepoint;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Explicit opt-in: applies pending Flyway migrations to the configured local PostgreSQL database.
 * Every test fixture is rolled back; only migrations and their reference roles remain.
 */
@EnabledIfEnvironmentVariable(named = "OPSFLOW_POSTGRES_TESTS", matches = "true")
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class DatabaseSchemaPostgresTests {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Test
    void createsAllBusinessTablesAndReferenceRoles() {
        List<String> tables = jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                """, String.class);

        assertThat(tables).contains("roles", "users", "user_roles", "software_services",
                "incidents", "incident_notes", "incident_status_history", "change_requests",
                "change_reviews", "change_status_history", "audit_logs", "flyway_schema_history");
        assertThat(jdbc.queryForList("SELECT code FROM roles", String.class))
                .containsExactlyInAnyOrder("ADMINISTRATOR", "DEVELOPER", "APPROVER");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM flyway_schema_history
                WHERE success AND version IN ('1', '2')
                """, Integer.class)).isEqualTo(2);
    }

    @Test
    void enforcesCaseInsensitiveUsernamesEmailsAndServiceCodes() throws Exception {
        String identifier = unique("schema");
        long userId = user(identifier, identifier + "@example.test");
        expectConstraintViolation(() -> user(identifier.toUpperCase(), unique("other") + "@example.test"));
        expectConstraintViolation(() -> user(unique("other"), identifier.toUpperCase() + "@example.test"));
        String serviceCode = unique("svc");
        service(userId, serviceCode);
        expectConstraintViolation(() -> service(userId, serviceCode.toUpperCase()));
    }

    @Test
    void validatesIncidentAssignmentResolutionAndClosure() throws Exception {
        long actor = user();
        long incident = incident(service(actor), actor, null);
        expectConstraintViolation(() -> jdbc.update("UPDATE incidents SET status = 'ASSIGNED' WHERE id = ?", incident));
        jdbc.update("UPDATE incidents SET status = 'ASSIGNED', assignee_id = ? WHERE id = ?", actor, incident);
        expectConstraintViolation(() -> jdbc.update("UPDATE incidents SET status = 'UNKNOWN' WHERE id = ?", incident));
        expectConstraintViolation(() -> jdbc.update("""
                UPDATE incidents SET status = 'RESOLVED', resolved_at = clock_timestamp() WHERE id = ?
                """, incident));
        jdbc.update("""
                UPDATE incidents SET status = 'RESOLVED', resolution = 'Fix verified',
                resolved_at = clock_timestamp() WHERE id = ?
                """, incident);
        expectConstraintViolation(() -> jdbc.update("UPDATE incidents SET status = 'CLOSED' WHERE id = ?", incident));
        jdbc.update("UPDATE incidents SET status = 'CLOSED', closed_at = clock_timestamp() WHERE id = ?", incident);
        assertThat(jdbc.queryForObject("SELECT status FROM incidents WHERE id = ?", String.class, incident))
                .isEqualTo("CLOSED");
    }

    @Test
    void allowsIncompleteDraftButRequiresPlansAndRiskBeforeSubmission() throws Exception {
        long actor = user();
        long change = draft(service(actor), actor, null);
        expectConstraintViolation(() -> jdbc.update("""
                UPDATE change_requests SET status = 'PENDING_APPROVAL', submission_number = 1,
                submitted_at = clock_timestamp() WHERE id = ?
                """, change));
        submit(change);
        expectConstraintViolation(() -> jdbc.update("UPDATE change_requests SET status = 'IMPLEMENTING' WHERE id = ?", change));
        jdbc.update("""
                UPDATE change_requests SET status = 'IMPLEMENTING', implementation_started_at = clock_timestamp()
                WHERE id = ?
                """, change);
        expectConstraintViolation(() -> jdbc.update("""
                UPDATE change_requests SET status = 'COMPLETED', implementation_finished_at = clock_timestamp()
                WHERE id = ?
                """, change));
        jdbc.update("""
                UPDATE change_requests SET status = 'COMPLETED', implementation_finished_at = clock_timestamp(),
                implementation_result = 'Deployment and health check passed' WHERE id = ?
                """, change);
        assertThat(jdbc.queryForObject("SELECT status FROM change_requests WHERE id = ?", String.class, change))
                .isEqualTo("COMPLETED");
    }

    @Test
    void requiresRejectionReasonAndOneDecisionPerSubmission() throws Exception {
        long requester = user();
        long reviewer = user();
        long change = draft(service(requester), requester, null);
        submit(change);
        expectConstraintViolation(() -> jdbc.update("""
                INSERT INTO change_reviews(change_request_id, submission_number, reviewer_id, decision)
                VALUES (?, 1, ?, 'REJECTED')
                """, change, reviewer));
        jdbc.update("""
                INSERT INTO change_reviews(change_request_id, submission_number, reviewer_id, decision, comment)
                VALUES (?, 1, ?, 'REJECTED', 'Rollback plan needs detail')
                """, change, reviewer);
        expectConstraintViolation(() -> jdbc.update("""
                INSERT INTO change_reviews(change_request_id, submission_number, reviewer_id, decision)
                VALUES (?, 1, ?, 'APPROVED')
                """, change, reviewer));
    }

    @Test
    void enforcesSameServiceForIncidentLinksAndPreventsDuplicateFailureIncident() throws Exception {
        long actor = user();
        long firstService = service(actor);
        long secondService = service(actor);
        long initialIncident = incident(firstService, actor, null);
        expectConstraintViolation(() -> draft(secondService, actor, initialIncident));
        long change = draft(firstService, actor, initialIncident);
        submit(change);
        jdbc.update("""
                UPDATE change_requests SET status = 'FAILED', implementation_started_at = clock_timestamp(),
                implementation_finished_at = clock_timestamp(), implementation_result = 'Health check failed'
                WHERE id = ?
                """, change);
        expectConstraintViolation(() -> incident(secondService, actor, change));
        long linkedIncident = incident(firstService, actor, change);
        expectConstraintViolation(() -> incident(firstService, actor, change));
        assertThat(jdbc.queryForObject("SELECT source_change_id FROM incidents WHERE id = ?", Long.class, linkedIncident))
                .isEqualTo(change);
    }

    @Test
    void storesRolesInvestigationNotesHistoriesAndStructuredAudit() {
        long actor = user();
        long service = service(actor);
        long incident = incident(service, actor, null);
        long change = draft(service, actor, incident);
        jdbc.update("""
                INSERT INTO user_roles(user_id, role_id, assigned_by)
                SELECT ?, id, ? FROM roles WHERE code = 'DEVELOPER'
                """, actor, actor);
        jdbc.update("INSERT INTO incident_notes(incident_id, author_id, content) VALUES (?, ?, 'Inspecting logs')", incident, actor);
        jdbc.update("""
                INSERT INTO incident_status_history(incident_id, new_status, changed_by)
                VALUES (?, 'OPEN', ?)
                """, incident, actor);
        jdbc.update("""
                INSERT INTO change_status_history(change_request_id, new_status, changed_by)
                VALUES (?, 'DRAFT', ?)
                """, change, actor);
        jdbc.update("""
                INSERT INTO audit_logs(actor_id, action, entity_type, entity_id, details)
                VALUES (?, 'INCIDENT_CREATED', 'INCIDENT', ?, '{"newStatus":"OPEN"}'::jsonb)
                """, actor, incident);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_roles WHERE user_id = ?", Integer.class, actor)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT content FROM incident_notes WHERE incident_id = ?", String.class, incident))
                .isEqualTo("Inspecting logs");
        assertThat(jdbc.queryForObject("""
                SELECT details ->> 'newStatus' FROM audit_logs
                WHERE entity_type = 'INCIDENT' AND entity_id = ?
                """, String.class, incident)).isEqualTo("OPEN");
    }

    @Test
    void preventsOrphanReferencesAndInvalidAuditDetails() throws Exception {
        long actor = user();
        expectConstraintViolation(() -> incident(-1, actor, null));
        expectConstraintViolation(() -> jdbc.update("INSERT INTO user_roles(user_id, role_id) VALUES (?, -1)", actor));
        expectConstraintViolation(() -> jdbc.update("""
                INSERT INTO audit_logs(actor_id, action, entity_type, entity_id, details)
                VALUES (?, 'USER_UPDATED', 'USER', ?, '[]'::jsonb)
                """, actor, actor));
    }

    @Test
    void maintainsUpdatedTimestampAutomatically() {
        long actor = user();
        jdbc.update("UPDATE users SET display_name = 'Updated', updated_at = '2000-01-01T00:00:00Z' WHERE id = ?", actor);
        OffsetDateTime updated = jdbc.queryForObject("SELECT updated_at FROM users WHERE id = ?", OffsetDateTime.class, actor);
        assertThat(updated).isAfter(OffsetDateTime.parse("2000-01-01T00:00:00Z"));
    }

    private long user() {
        String identifier = unique("user");
        return user(identifier, identifier + "@example.test");
    }

    private long user(String username, String email) {
        return jdbc.queryForObject("""
                INSERT INTO users(username, email, display_name, password_hash)
                VALUES (?, ?, 'Schema test', 'test-only-encoded-value') RETURNING id
                """, Long.class, username, email);
    }

    private long service(long actor) {
        return service(actor, unique("svc"));
    }

    private long service(long actor, String code) {
        return jdbc.queryForObject("""
                INSERT INTO software_services(code, name, owner_id, created_by)
                VALUES (?, 'Schema test service', ?, ?) RETURNING id
                """, Long.class, code, actor, actor);
    }

    private long incident(long service, long actor, Long sourceChange) {
        return jdbc.queryForObject("""
                INSERT INTO incidents(service_id, reporter_id, title, description, severity, source_change_id)
                VALUES (?, ?, 'Test incident', 'Test description', 'HIGH', ?) RETURNING id
                """, Long.class, service, actor, sourceChange);
    }

    private long draft(long service, long actor, Long relatedIncident) {
        return jdbc.queryForObject("""
                INSERT INTO change_requests(service_id, requester_id, related_incident_id, title)
                VALUES (?, ?, ?, 'Test change') RETURNING id
                """, Long.class, service, actor, relatedIncident);
    }

    private void submit(long change) {
        jdbc.update("""
                UPDATE change_requests SET status = 'PENDING_APPROVAL', submission_number = 1,
                submitted_at = clock_timestamp(), business_reason = 'Fix incident', risk_level = 'LOW',
                planned_implementation_at = CURRENT_TIMESTAMP + INTERVAL '1 day',
                implementation_plan = 'Apply fix', rollback_plan = 'Restore previous version' WHERE id = ?
                """, change);
    }

    private void expectConstraintViolation(Runnable operation) throws Exception {
        Connection connection = DataSourceUtils.getConnection(dataSource);
        Savepoint savepoint = connection.setSavepoint();
        try {
            assertThatThrownBy(operation::run).isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            connection.rollback(savepoint);
            connection.releaseSavepoint(savepoint);
        }
    }

    private String unique(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }
}
