package com.opsflow;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.opsflow.audit.AuditService;
import com.opsflow.users.Role;
import com.opsflow.users.RoleCode;
import com.opsflow.users.RoleRepository;
import com.opsflow.users.UserDtos.CreateUserRequest;
import com.opsflow.users.UserRepository;
import com.opsflow.users.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuditLogApiTests {
    private static final String ROOT = "/api/v1/audit-logs";
    private static final String PASSWORD = "AuditTestPassword123!";
    @Autowired private TestRestTemplate http;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RoleRepository roles;
    @Autowired private UserRepository accounts;
    @Autowired private UserService users;
    @Autowired private AuditService audit;
    @Autowired private PlatformTransactionManager transactions;
    private long adminId;
    private long developerId;
    private String adminToken;
    private String developerToken;
    private String approverToken;

    @BeforeEach
    void isolatedAccountsAndTokens() {
        H2TestData.reset(jdbc);
        for (RoleCode code : RoleCode.values()) roles.saveAndFlush(new Role(code, code.name()));
        users.bootstrapAdministrator("auditadmin", "auditadmin@example.test", "Audit administrator", PASSWORD);
        adminId = accounts.findByUsernameIgnoreCase("auditadmin").orElseThrow().getId();
        developerId = users.create(new CreateUserRequest("auditdeveloper", "auditdeveloper@example.test", "Developer",
                PASSWORD, Set.of(RoleCode.DEVELOPER)), adminId).id();
        users.create(new CreateUserRequest("auditapprover", "auditapprover@example.test", "Approver",
                PASSWORD, Set.of(RoleCode.APPROVER)), adminId);
        adminToken = login("auditadmin");
        developerToken = login("auditdeveloper");
        approverToken = login("auditapprover");
    }

    @Test
    void requiresAdministratorOnBothEndpoints() {
        long id = event(adminId, "SERVICE_CREATED", 91, "2026-10-01T00:00:00Z", Map.of());
        for (String path : List.of(ROOT, ROOT + "/" + id)) {
            assertError(request(HttpMethod.GET, path, null), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
            assertError(request(HttpMethod.GET, path, "invalid-jwt"), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
            for (String token : List.of(developerToken, approverToken)) {
                assertError(request(HttpMethod.GET, path, token), HttpStatus.FORBIDDEN, "FORBIDDEN");
            }
            assertThat(request(HttpMethod.GET, path, adminToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    void returnsSafeSummaryAndDetailedJsonIncludingNullValues() {
        var details = new LinkedHashMap<String, Object>();
        details.put("previous", Map.of("name", "旧名称", "enabled", true));
        details.put("ownerId", null);
        long id = event(adminId, "SERVICE_UPDATED", 92, "2026-10-01T00:00:00Z", details);
        var list = get("?entityType=SERVICE&entityId=92");
        var summary = list.get("items").get(0);
        assertThat(summary.get("id").asLong()).isEqualTo(id);
        assertThat(summary.has("details")).isFalse();
        assertThat(summary.get("actor").get("id").asLong()).isEqualTo(adminId);
        assertThat(summary.get("actor").get("username").asText()).isEqualTo("auditadmin");
        assertThat(summary.get("actor").size()).isEqualTo(3);
        var detail = get("/" + id);
        assertThat(detail.get("details").get("previous").get("name").asText()).isEqualTo("旧名称");
        assertThat(detail.get("details").get("ownerId").isNull()).isTrue();
        assertThat(detail.toString()).doesNotContain("password", "email", "roleAssignments", "accessToken", PASSWORD, adminToken);
    }

    @Test
    void paginatesByTimeThenDescendingIdWithStableTies() {
        long oldest = event(adminId, "SERVICE_CREATED", 93, "2026-10-01T00:00:00Z", Map.of());
        long newest = event(adminId, "SERVICE_CREATED", 93, "2026-10-03T00:00:00Z", Map.of());
        long tied = event(adminId, "SERVICE_CREATED", 93, "2026-10-03T00:00:00Z", Map.of());
        long lastInsertedButOlder = event(adminId, "SERVICE_CREATED", 93, "2026-10-02T00:00:00Z", Map.of());
        String filter = "?entityType=SERVICE&entityId=93&size=2";
        var first = get(filter);
        assertThat(first.get("totalElements").asLong()).isEqualTo(4);
        assertThat(first.get("totalPages").asInt()).isEqualTo(2);
        assertThat(first.get("page").asInt()).isZero();
        assertThat(first.get("size").asInt()).isEqualTo(2);
        assertThat(first.get("items").get(0).get("id").asLong()).isEqualTo(tied);
        assertThat(first.get("items").get(1).get("id").asLong()).isEqualTo(newest);
        var second = get(filter + "&page=1");
        assertThat(second.get("items").get(0).get("id").asLong()).isEqualTo(lastInsertedButOlder);
        assertThat(second.get("items").get(1).get("id").asLong()).isEqualTo(oldest);
        assertThat(get(filter + "&page=99").get("items").isEmpty()).isTrue();
        assertThat(get("").get("size").asInt()).isEqualTo(20);
    }

    @Test
    void combinesEveryFilterWithInclusiveFromAndExclusiveTo() {
        event(adminId, "SERVICE_CREATED", 94, "2026-10-01T00:00:00Z", Map.of());
        event(adminId, "SERVICE_CREATED", 94, "2026-10-02T00:00:00Z", Map.of());
        event(developerId, "SERVICE_CREATED", 94, "2026-10-01T00:00:00Z", Map.of());
        event(adminId, "SERVICE_UPDATED", 94, "2026-10-01T00:00:00Z", Map.of());
        event(adminId, "SERVICE_CREATED", 95, "2026-10-01T00:00:00Z", Map.of());
        var result = get("?actorId=" + adminId + "&action=SERVICE_CREATED&entityType=SERVICE&entityId=94"
                + "&from=2026-10-01T00:00:00Z&to=2026-10-02T00:00:00Z");
        assertThat(result.get("totalElements").asLong()).isEqualTo(1);
        assertThat(result.get("items").get(0).get("occurredAt").asText()).isEqualTo("2026-10-01T00:00:00Z");
        assertThat(get("?entityType=SERVICE&entityId=94&to=2026-10-01T00:00:00Z").get("totalElements").asLong()).isZero();
        assertThat(get("?entityType=SERVICE&entityId=94&from=2026-10-02T00:00:00Z").get("totalElements").asLong()).isEqualTo(1);
        assertThat(get("?entityType=SERVICE&entityId=94&from=2026-10-01T08:00:00%2B08:00&to=2026-10-02T00:00:00Z")
                .get("totalElements").asLong()).isEqualTo(3);
    }

    @Test
    void acceptsSystemEventsWithNullActorAndEmptyDetails() {
        long id = event(null, "SERVICE_CREATED", 96, "2026-10-01T00:00:00Z", Map.of());
        assertThat(get("/" + id).get("actor").isNull()).isTrue();
        assertThat(get("/" + id).get("details").isEmpty()).isTrue();
        assertThat(get("?entityId=96&entityType=SERVICE").get("items").get(0).get("actor").isNull()).isTrue();
    }

    @Test
    void returnsEmptyForUnmatchedFiltersAndDoesNotTreatInputAsSql() {
        for (String query : List.of("actorId=" + Long.MAX_VALUE, "entityId=" + Long.MAX_VALUE,
                "action=UNKNOWN_ACTION", "action=service_created", "action=%27%20OR%201%3D1--", "entityType=ROLE")) {
            assertThat(get("?" + query).get("totalElements").asLong()).as(query).isZero();
        }
    }

    @Test
    void rejectsInvalidIdsPaginationActionEntityTypeAndDates() {
        for (String query : List.of("page=-1", "page=wrong", "size=0", "size=101", "actorId=0", "actorId=-1",
                "entityId=0", "entityId=wrong", "entityType=UNKNOWN", "action=", "action=%20%20",
                "action=" + "a".repeat(81), "from=invalid", "to=invalid", "from=2026-10-01T00:00:00")) {
            assertError(request(HttpMethod.GET, ROOT + "?" + query, adminToken), HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        }
        for (String id : List.of("0", "-1", "wrong", "9223372036854775808")) {
            assertError(request(HttpMethod.GET, ROOT + "/" + id, adminToken), HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        }
    }

    @Test
    void rejectsReversedOrEmptyTimeIntervals() {
        for (String to : List.of("2026-10-01T00:00:00Z", "2026-09-01T00:00:00Z")) {
            assertError(request(HttpMethod.GET, ROOT + "?from=2026-10-01T00:00:00Z&to=" + to, adminToken),
                    HttpStatus.BAD_REQUEST, "INVALID_TIME_RANGE");
        }
    }

    @Test
    void missingDetailReturnsNotFoundWithoutLeakingItToOtherRoles() {
        assertError(request(HttpMethod.GET, ROOT + "/" + Long.MAX_VALUE, adminToken),
                HttpStatus.NOT_FOUND, "AUDIT_LOG_NOT_FOUND");
        assertError(request(HttpMethod.GET, ROOT + "/" + Long.MAX_VALUE, developerToken),
                HttpStatus.FORBIDDEN, "FORBIDDEN");
    }

    @Test
    void queriesAndUnsupportedWritesDoNotMutateAuditRecords() {
        long id = event(adminId, "SERVICE_CREATED", 97, "2026-10-01T00:00:00Z", Map.of("name", "Original"));
        long count = jdbc.queryForObject("SELECT count(*) FROM audit_logs", Long.class);
        var before = get("/" + id);
        get("");
        for (HttpMethod method : List.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)) {
            for (String path : List.of(ROOT, ROOT + "/" + id)) {
                assertThat(request(method, path, adminToken).getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
                assertThat(request(method, path, developerToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            }
        }
        assertThat(get("/" + id)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs", Long.class)).isEqualTo(count);
    }

    @Test
    void revokedRoleAndDisabledAccountTakeEffectForExistingToken() {
        jdbc.update("DELETE FROM user_roles WHERE user_id=?", adminId);
        assertError(request(HttpMethod.GET, ROOT, adminToken), HttpStatus.FORBIDDEN, "FORBIDDEN");
        jdbc.update("UPDATE users SET enabled=false WHERE id=?", adminId);
        assertError(request(HttpMethod.GET, ROOT, adminToken), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
    }

    private long event(Long actorId, String action, long entityId, String occurredAt, Map<String, Object> details) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> audit.serviceEvent(
                actorId == null ? null : accounts.findById(actorId).orElseThrow(), action, entityId, details));
        long id = jdbc.queryForObject("SELECT max(id) FROM audit_logs", Long.class);
        jdbc.update("UPDATE audit_logs SET occurred_at=? WHERE id=?",
                OffsetDateTime.ofInstant(Instant.parse(occurredAt), ZoneOffset.UTC), id);
        return id;
    }

    private JsonNode get(String suffix) {
        var response = request(HttpMethod.GET, ROOT + suffix, adminToken);
        assertThat(response.getStatusCode()).as(suffix).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private String login(String username) {
        return http.postForEntity("/api/v1/auth/login", Map.of("username", username, "password", PASSWORD), JsonNode.class)
                .getBody().get("accessToken").asText();
    }

    private ResponseEntity<JsonNode> request(HttpMethod method, String path, String token) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) headers.setBearerAuth(token);
        return http.exchange(java.net.URI.create(http.getRootUri() + path), method,
                new HttpEntity<>(null, headers), JsonNode.class);
    }

    private void assertError(ResponseEntity<JsonNode> response, HttpStatus status, String code) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody().get("code").asText()).isEqualTo(code);
    }
}
