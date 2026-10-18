package com.opsflow;

import java.util.Set;
import java.util.UUID;
import com.opsflow.incidents.*;
import com.opsflow.incidents.IncidentDtos.*;
import com.opsflow.services.ServiceDtos.SaveServiceRequest;
import com.opsflow.services.SoftwareServiceManager;
import com.opsflow.users.RoleCode;
import com.opsflow.users.UserDtos.CreateUserRequest;
import com.opsflow.users.UserService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "OPSFLOW_POSTGRES_TESTS", matches = "true")
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class IncidentPostgresTests {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;
    @Autowired private IncidentService incidents;
    @Autowired private SoftwareServiceManager services;
    @Autowired private UserService users;
    private long actorId, developerId, serviceId;

    @BeforeEach
    void transactionScopedFixtures() {
        String actor = unique("pgincadmin");
        actorId = jdbc.queryForObject("INSERT INTO users(username,email,display_name,password_hash) VALUES (?,?,'Incident test admin','not-a-login-fixture') RETURNING id", Long.class, actor, actor + "@example.test");
        jdbc.update("INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE code='ADMINISTRATOR'", actorId);
        String developer = unique("pgincdev");
        developerId = users.create(new CreateUserRequest(developer, developer + "@example.test", "Incident test developer", "PostgresIncidentPass123!", Set.of(RoleCode.DEVELOPER)), actorId).id();
        serviceId = services.create(new SaveServiceRequest(unique("pgincsvc"), "Incident service", null, developerId), actorId).id();
    }

    @Test
    void completeLifecycleAndAggregateVersionWorkAgainstRealConstraintsAndTriggers() {
        var created = incidents.create(new CreateIncidentRequest(serviceId, "Postgres incident", "Real schema verification", IncidentSeverity.CRITICAL), developerId);
        var assigned = incidents.assign(created.id(), new AssignIncidentRequest(developerId, created.version(), "Please investigate"), actorId);
        var investigating = incidents.transition(created.id(), new TransitionRequest(IncidentStatus.INVESTIGATING, assigned.version(), null, null), developerId);
        var note = incidents.addNote(created.id(), new AddNoteRequest("Found connection exhaustion", investigating.version()), developerId);
        entityManager.flush(); entityManager.clear();
        var afterNote = incidents.get(created.id(), developerId);
        assertThat(afterNote.version()).isEqualTo(3);
        assertThat(afterNote.updatedAt()).isAfter(created.updatedAt());
        var resolved = incidents.transition(created.id(), new TransitionRequest(IncidentStatus.RESOLVED, afterNote.version(), "Fixed pool configuration", null), developerId);
        var closed = incidents.transition(created.id(), new TransitionRequest(IncidentStatus.CLOSED, resolved.version(), null, "Confirmed recovery"), actorId);
        entityManager.flush(); entityManager.clear();
        var stored = incidents.get(created.id(), actorId);
        assertThat(stored).isEqualTo(closed);
        assertThat(stored.resolvedAt()).isAfterOrEqualTo(stored.createdAt());
        assertThat(stored.closedAt()).isAfterOrEqualTo(stored.resolvedAt());
        assertThat(incidents.history(created.id(), 0, 20).totalElements()).isEqualTo(5);
        assertThat(incidents.notes(created.id(), 0, 20).items().getFirst().id()).isEqualTo(note.id());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE entity_type='INCIDENT' AND entity_id=?", Long.class, created.id())).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT details->>'newStatus' FROM audit_logs WHERE action='INCIDENT_STATUS_CHANGED' AND entity_type='INCIDENT' AND entity_id=? ORDER BY id DESC LIMIT 1", String.class, created.id())).isEqualTo("CLOSED");
    }

    @Test
    void mapsOpenNullableFieldsAndDatabaseFilteredCounts() {
        var created = incidents.create(new CreateIncidentRequest(serviceId, "Open incident", "Awaiting assignment", IncidentSeverity.LOW), actorId);
        entityManager.flush(); entityManager.clear();
        var stored = incidents.get(created.id(), actorId);
        assertThat(stored.assignee()).isNull(); assertThat(stored.sourceChangeId()).isNull();
        assertThat(stored.resolvedAt()).isNull(); assertThat(stored.closedAt()).isNull();
        var filtered = incidents.list(0, 1, IncidentStatus.OPEN, IncidentSeverity.LOW, serviceId, null, actorId);
        assertThat(filtered.totalElements()).isEqualTo(1);
        assertThat(filtered.items().getFirst().id()).isEqualTo(created.id());
        assertThat(incidents.history(created.id(), 0, 1).items().getFirst().oldStatus()).isNull();
    }

    @Test
    void assignmentCandidateQueryDeduplicatesMultiRoleAccounts() {
        users.replaceRoles(developerId, Set.of(RoleCode.DEVELOPER, RoleCode.ADMINISTRATOR), actorId);
        var candidates = incidents.assignees(0, 100, actorId);
        assertThat(candidates.items().stream().filter(person -> person.id() == developerId).count()).isEqualTo(1);
    }
    private String unique(String prefix) { return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 12); }
}
