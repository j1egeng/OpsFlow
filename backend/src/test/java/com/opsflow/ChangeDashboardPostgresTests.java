package com.opsflow;
import java.time.*;
import java.util.Set;
import java.util.UUID;
import com.opsflow.changes.*;
import com.opsflow.changes.ChangeDtos.*;
import com.opsflow.dashboard.DashboardService;
import com.opsflow.incidents.*;
import com.opsflow.incidents.IncidentDtos.CreateIncidentRequest;
import com.opsflow.services.ServiceDtos.SaveServiceRequest;
import com.opsflow.services.SoftwareServiceManager;
import com.opsflow.users.*;
import com.opsflow.users.UserDtos.CreateUserRequest;
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

@EnabledIfEnvironmentVariable(named="OPSFLOW_POSTGRES_TESTS",matches="true")
@ActiveProfiles("local") @SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE) @Transactional
class ChangeDashboardPostgresTests {
    @Autowired private ChangeService changes;
    @Autowired private DashboardService dashboard;
    @Autowired private IncidentService incidents;
    @Autowired private UserService users;
    @Autowired private SoftwareServiceManager services;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;
    private long actor, reviewer, service;
    @BeforeEach void fixtures() {
        String name=unique("pgchg");
        actor=jdbc.queryForObject("INSERT INTO users(username,email,display_name,password_hash) VALUES (?,?,'Change test admin','not-a-login-fixture') RETURNING id",Long.class,name,name+"@example.test");
        jdbc.update("INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE code='ADMINISTRATOR'",actor);
        String approver=unique("pgreview");
        reviewer=users.create(new CreateUserRequest(approver,approver+"@example.test","Approver","PostgresChangePass123!",Set.of(RoleCode.APPROVER)),actor).id();
        service=services.create(new SaveServiceRequest(unique("pgsvc"),"Change service",null,null),actor).id();
    }
    @Test void failurePersistsSameServiceForeignKeysHistoriesAndJsonbAuditAtomically() {
        var linked=incidents.create(new CreateIncidentRequest(service,"Initial incident","Initial failure",IncidentSeverity.HIGH),actor);
        var change=changes.create(draft(linked.id()),actor);
        change=changes.submit(change.id(),change.version(),actor);
        change=changes.review(change.id(),new ReviewRequest(change.version(),ReviewDecision.APPROVED,"Approved"),reviewer);
        change=changes.start(change.id(),change.version(),actor);
        change=changes.finish(change.id(),new ResultRequest(change.version(),ImplementationOutcome.FAILED,"Implementation failed, rollback verified"),actor);
        entityManager.flush();entityManager.clear();
        var stored=changes.get(change.id(),actor);
        assertThat(stored).isEqualTo(change);
        var incident=incidents.get(stored.failureIncidentId(),actor);
        assertThat(incident.sourceChangeId()).isEqualTo(stored.id());assertThat(incident.service().id()).isEqualTo(service);
        assertThat(stored.relatedIncidentId()).isEqualTo(linked.id());
        assertThat(stored.implementationStartedAt()).isAfterOrEqualTo(stored.submittedAt());
        assertThat(stored.implementationFinishedAt()).isAfterOrEqualTo(stored.implementationStartedAt());
        assertThat(jdbc.queryForObject("SELECT details->>'outcome' FROM audit_logs WHERE entity_type='CHANGE_REQUEST' AND entity_id=? AND action='CHANGE_FINISHED'",String.class,stored.id())).isEqualTo("FAILED");
        assertThat(changes.history(stored.id(),0,100).totalElements()).isEqualTo(5);
    }
    @Test void rejectedRoundIsRetainedWhenDraftIsEditedAndResubmitted() {
        var change=changes.create(draft(null),actor);change=changes.submit(change.id(),change.version(),actor);
        change=changes.review(change.id(),new ReviewRequest(change.version(),ReviewDecision.REJECTED,"Improve plan"),reviewer);
        var request=draft(null);change=changes.update(change.id(),new DraftRequest(service,null,request.title(),request.businessReason(),request.riskLevel(),request.plannedImplementationAt(),request.implementationPlan(),"Improved rollback",change.version()),actor);
        change=changes.submit(change.id(),change.version(),actor);change=changes.review(change.id(),new ReviewRequest(change.version(),ReviewDecision.APPROVED,null),reviewer);
        entityManager.flush();entityManager.clear();
        assertThat(changes.reviews(change.id(),0,20).totalElements()).isEqualTo(2);
        assertThat(changes.get(change.id(),actor).submissionNumber()).isEqualTo(2);
    }
    @Test void dashboardSqlUsesRealPostgresUtcAggregationAndAllDatabaseRows() {
        var before=dashboard.summary();
        var change=changes.create(draft(null),actor);change=changes.submit(change.id(),change.version(),actor);entityManager.flush();
        var after=dashboard.summary();
        assertThat(after.totalChanges()).isEqualTo(before.totalChanges()+1);
        assertThat(after.pendingApprovals()).isEqualTo(before.pendingApprovals()+1);
        assertThat(after.trend().size()).isEqualTo(7);
        assertThat(after.trend().getLast().changes()).isEqualTo(before.trend().getLast().changes()+1);
    }
    private DraftRequest draft(Long related){return new DraftRequest(service,related,"Postgres change","Service recovery",RiskLevel.HIGH,Instant.now().plusSeconds(86400),"Implement change","Rollback change",null);}
    private String unique(String prefix){return prefix+UUID.randomUUID().toString().replace("-","").substring(0,12);}
}
