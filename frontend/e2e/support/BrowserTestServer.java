import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;

import com.opsflow.OpsFlowApplication;
import com.opsflow.services.SoftwareServiceManager;
import com.opsflow.services.ServiceDtos.SaveServiceRequest;
import com.opsflow.incidents.IncidentService;
import com.opsflow.incidents.IncidentStatus;
import com.opsflow.incidents.IncidentSeverity;
import com.opsflow.incidents.IncidentDtos.*;
import com.opsflow.changes.ChangeService;
import com.opsflow.changes.RiskLevel;
import com.opsflow.changes.ReviewDecision;
import com.opsflow.changes.ImplementationOutcome;
import com.opsflow.changes.ChangeDtos.*;
import java.time.Instant;
import com.opsflow.users.Role;
import com.opsflow.users.RoleCode;
import com.opsflow.users.RoleRepository;
import com.opsflow.users.UserDtos.CreateUserRequest;
import com.opsflow.users.UserRepository;
import com.opsflow.users.UserService;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Test-only server: real application HTTP endpoints against isolated, disposable H2 data. */
class BrowserTestServer {
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(OpsFlowApplication.class, Fixtures.class);
        application.setAdditionalProfiles("test");
        application.run("--server.port=18081", "--server.address=127.0.0.1",
                "--spring.datasource.url=jdbc:h2:mem:opsflow-browser;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "--spring.datasource.username=sa", "--spring.datasource.password=",
                "--spring.datasource.driver-class-name=org.h2.Driver", "--spring.flyway.enabled=false",
                "--spring.jpa.hibernate.ddl-auto=create-drop",
                "--app.cors.allowed-origins=http://127.0.0.1:4173,http://localhost:4173",
                "--app.bootstrap.admin.enabled=false");
    }

    @Configuration(proxyBeanMethods = false)
    static class Fixtures {
        @Bean
        AtomicBoolean fixturesReady() { return new AtomicBoolean(false); }

        @Bean
        HealthIndicator browserFixtures(AtomicBoolean fixturesReady) {
            return () -> fixturesReady.get() ? Health.up().build() : Health.down().build();
        }

        @Bean
        ApplicationRunner createFixtures(RoleRepository roles, UserRepository repository,
                                         UserService users, SoftwareServiceManager services, IncidentService incidents, ChangeService changes, AtomicBoolean fixturesReady, DataSource dataSource) {
            return arguments -> {
                try (var connection = dataSource.getConnection()) {
                    if (!connection.getMetaData().getURL().startsWith("jdbc:h2:mem:opsflow-browser")) {
                        throw new IllegalStateException("Browser fixtures require the isolated in-memory H2 database");
                    }
                }
                for (RoleCode role : RoleCode.values()) roles.saveAndFlush(new Role(role, role.name()));
                String password = "BrowserTestPass123!";
                users.bootstrapAdministrator("browseradmin", "browseradmin@example.test", "测试管理员", password);
                long actor = repository.findByUsernameIgnoreCase("browseradmin").orElseThrow().getId();
                users.create(new CreateUserRequest("developer", "developer@example.test", "测试开发者", password,
                        Set.of(RoleCode.DEVELOPER)), actor);
                users.create(new CreateUserRequest("approver", "approver@example.test", "测试审批人", password,
                        Set.of(RoleCode.APPROVER)), actor);
                for (int i = 1; i <= 13; i++) {
                    String username = "member" + String.format("%02d", i);
                    users.create(new CreateUserRequest(username, username + "@example.test", "测试成员 " + i,
                            password, Set.of(i % 3 == 0 ? RoleCode.APPROVER : RoleCode.DEVELOPER)), actor);
                }
                long developer = repository.findByUsernameIgnoreCase("developer").orElseThrow().getId();
                long disabledOwner = repository.findByUsernameIgnoreCase("member12").orElseThrow().getId();
                for (int i = 1; i <= 13; i++) {
                    var service = services.create(new SaveServiceRequest("catalog-" + String.format("%02d", i),
                            "Catalog service " + i, "Service maintained by Team 13", i == 13 ? disabledOwner : developer), actor);
                    if (i == 13) services.setEnabled(service.id(), false, actor);
                }
                users.setEnabled(disabledOwner, false, actor);
                long firstService = services.list(0, 1, true).items().getFirst().id();
                long otherDeveloper = repository.findByUsernameIgnoreCase("member01").orElseThrow().getId();
                for (int i = 1; i <= 12; i++) {
                    var incident = incidents.create(new CreateIncidentRequest(firstService, "Seed incident " + String.format("%02d", i),
                            "Browser incident verification", i % 2 == 0 ? IncidentSeverity.HIGH : IncidentSeverity.MEDIUM), developer);
                    if (i == 1 || i == 2 || i == 12) {
                        incident = incidents.assign(incident.id(), new AssignIncidentRequest(i == 2 ? otherDeveloper : developer, incident.version(), "Seed assignment"), actor);
                    }
                    if (i == 1 || i == 12) {
                        incident = incidents.transition(incident.id(), new TransitionRequest(IncidentStatus.INVESTIGATING, incident.version(), null, "Investigation started"), developer);
                        for (int n = 1; n <= (i == 1 ? 12 : 1); n++) {
                            incidents.addNote(incident.id(), new AddNoteRequest("Seed investigation note " + n, incident.version()), developer);
                            incident = incidents.get(incident.id(), developer);
                        }
                    }
                    if (i == 12) {
                        incident = incidents.transition(incident.id(), new TransitionRequest(IncidentStatus.RESOLVED, incident.version(), "Verified service recovery", null), developer);
                        incidents.transition(incident.id(), new TransitionRequest(IncidentStatus.CLOSED, incident.version(), null, "Reviewed by admin"), actor);
                    }
                }
                users.create(new CreateUserRequest("dualrole", "dualrole@example.test", "Multi-role tester", password,
                        Set.of(RoleCode.DEVELOPER, RoleCode.APPROVER)), actor);
                long approver = repository.findByUsernameIgnoreCase("approver").orElseThrow().getId();
                for (int i = 1; i <= 13; i++) {
                    var change = changes.create(new DraftRequest(firstService, null, "Seed change " + String.format("%02d", i),
                            "Improve service reliability", RiskLevel.MEDIUM, Instant.now().plusSeconds(86400), "Apply configuration", "Restore configuration", null), developer);
                    if (i <= 6) change = changes.submit(change.id(), change.version(), developer);
                    if (i <= 6) change = changes.review(change.id(), new ReviewRequest(change.version(), i == 3 ? ReviewDecision.REJECTED : ReviewDecision.APPROVED, i == 3 ? "Improve rollback plan" : "Plan reviewed"), approver);
                    if (i == 4 || i == 5 || i == 6) change = changes.start(change.id(), change.version(), developer);
                    if (i == 5 || i == 6) changes.finish(change.id(), new ResultRequest(change.version(), i == 5 ? ImplementationOutcome.COMPLETED : ImplementationOutcome.FAILED, "Seed implementation outcome"), developer);
                }
                var pending = changes.create(new DraftRequest(firstService, null, "Pending review fixture", "Service reliability", RiskLevel.LOW,
                        Instant.now().plusSeconds(86400), "Apply configuration", "Restore configuration", null), developer);
                changes.submit(pending.id(), pending.version(), developer);
                fixturesReady.set(true);
                System.out.println("FRONTEND_E2E_READY: isolated H2 fixtures created; PostgreSQL is not used.");
            };
        }
    }
}
