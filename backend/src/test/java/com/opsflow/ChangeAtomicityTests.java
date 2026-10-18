package com.opsflow;
import java.time.Instant;
import java.util.Set;
import com.opsflow.changes.*;
import com.opsflow.changes.ChangeDtos.*;
import com.opsflow.common.ApiException;
import com.opsflow.incidents.IncidentService;
import com.opsflow.incidents.IncidentSeverity;
import com.opsflow.services.ServiceDtos.SaveServiceRequest;
import com.opsflow.services.SoftwareServiceManager;
import com.opsflow.users.*;
import com.opsflow.users.UserDtos.CreateUserRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doThrow;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties="spring.datasource.url=jdbc:h2:mem:change-atomic;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
class ChangeAtomicityTests {
    @Autowired private ChangeService changes;
    @Autowired private UserService users;
    @Autowired private UserRepository accounts;
    @Autowired private RoleRepository roles;
    @Autowired private SoftwareServiceManager services;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private IncidentService incidents;
    private long actor;
    private ChangeResponse implementing;
    @BeforeEach void setup() {
        H2TestData.reset(jdbc);
        for(var role:RoleCode.values()) roles.saveAndFlush(new Role(role,role.name()));
        users.bootstrapAdministrator("atomicadmin","atomicadmin@example.test","Admin","AtomicityTestPass123!");
        actor=accounts.findByUsernameIgnoreCase("atomicadmin").orElseThrow().getId();
        long reviewer=users.create(new CreateUserRequest("atomicapprover","atomicapprover@example.test","Approver","AtomicityTestPass123!",Set.of(RoleCode.APPROVER)),actor).id();
        long service=services.create(new SaveServiceRequest("atomic-service","Service",null,null),actor).id();
        var change=changes.create(new DraftRequest(service,null,"Atomicity test","Reason",RiskLevel.HIGH,Instant.now().plusSeconds(86400),"Implement","Rollback",null),actor);
        change=changes.submit(change.id(),change.version(),actor);
        change=changes.review(change.id(),new ReviewRequest(change.version(),ReviewDecision.APPROVED,null),reviewer);
        implementing=changes.start(change.id(),change.version(),actor);
    }
    @Test void incidentCreationFailureRollsBackResultHistoryAndAuditTogether() {
        long before=jdbc.queryForObject("SELECT count(*) FROM audit_logs",Long.class);
        IncidentService target = AopTestUtils.getUltimateTargetObject(incidents);
        doThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"TEST_INCIDENT_FAILURE","Controlled test failure"))
                .when(target).createFromFailedChange(anyLong(),anyLong(),anyLong(),anyString(),anyString(),any(IncidentSeverity.class));
        assertThatThrownBy(()->changes.finish(implementing.id(),new ResultRequest(implementing.version(),ImplementationOutcome.FAILED,"Failed deployment"),actor)).isInstanceOf(ApiException.class);
        var stored=changes.get(implementing.id(),actor);
        assertThat(stored.status()).isEqualTo(ChangeStatus.IMPLEMENTING); assertThat(stored.version()).isEqualTo(implementing.version());
        assertThat(stored.implementationResult()).isNull(); assertThat(stored.failureIncidentId()).isNull();
        assertThat(changes.history(stored.id(),0,100).totalElements()).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM incidents",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs",Long.class)).isEqualTo(before);
    }
}
