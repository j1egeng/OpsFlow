package com.opsflow;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.opsflow.common.ApiException;
import com.opsflow.services.ServiceDtos.SaveServiceRequest;
import com.opsflow.services.SoftwareServiceManager;
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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SoftwareServiceApiTests {
    private static final String ROOT = "/api/v1/services";
    private static final String PASSWORD = "ServiceTestPassword123!";

    @Autowired private TestRestTemplate http;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RoleRepository roles;
    @Autowired private UserRepository accounts;
    @Autowired private UserService users;
    @Autowired private SoftwareServiceManager services;

    private long adminId;
    private long developerId;
    private String adminToken;
    private String developerToken;
    private String approverToken;

    @BeforeEach
    void prepareIsolatedInMemoryAccounts() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:");
        }
        H2TestData.reset(jdbc);
        for (RoleCode code : RoleCode.values()) {
            roles.saveAndFlush(new Role(code, code.name()));
        }
        users.bootstrapAdministrator("svcadmin", "svcadmin@example.test", "Administrator", PASSWORD);
        adminId = accounts.findByUsernameIgnoreCase("svcadmin").orElseThrow().getId();
        developerId = users.create(new CreateUserRequest("svcdeveloper", "svcdeveloper@example.test", "Developer",
                PASSWORD, java.util.Set.of(RoleCode.DEVELOPER)), adminId).id();
        users.create(new CreateUserRequest("svcapprover", "svcapprover@example.test", "Approver",
                PASSWORD, java.util.Set.of(RoleCode.APPROVER)), adminId);
        adminToken = login("svcadmin");
        developerToken = login("svcdeveloper");
        approverToken = login("svcapprover");
    }

    @Test
    void createsServiceUsingAuthenticatedActorAndSafeOwnerSummary() {
        Map<String, Object> payload = body("PAYMENT-API", developerId);
        payload.put("name", "  支付服务  ");
        payload.put("description", "  支付状态  ");
        payload.put("createdBy", developerId);
        payload.put("enabled", false);
        var response = request(HttpMethod.POST, ROOT, adminToken, payload);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode service = response.getBody();
        assertThat(service.get("code").asText()).isEqualTo("payment-api");
        assertThat(service.get("name").asText()).isEqualTo("支付服务");
        assertThat(service.get("description").asText()).isEqualTo("支付状态");
        assertThat(service.get("createdBy").asLong()).isEqualTo(adminId);
        assertThat(service.get("enabled").asBoolean()).isTrue();
        assertThat(service.get("ownerId").asLong()).isEqualTo(developerId);
        assertThat(service.get("owner").get("username").asText()).isEqualTo("svcdeveloper");
        assertThat(service.get("createdAt").isNull()).isFalse();
        assertThat(service.get("updatedAt").isNull()).isFalse();
        assertThat(service.get("version").asLong()).isZero();
        assertThat(service.toString()).doesNotContain("password", "email", PASSWORD, "roleAssignments");
        assertThat(request(HttpMethod.GET, path(service), developerToken, null).getBody()).isEqualTo(service);
        long id = service.get("id").asLong();
        assertThat(jdbc.queryForObject("SELECT created_by FROM software_services WHERE id = ?", Long.class, id))
                .isEqualTo(adminId);
        assertThat(jdbc.queryForObject("SELECT actor_id FROM audit_logs WHERE action='SERVICE_CREATED' AND entity_id=?",
                Long.class, id)).isEqualTo(adminId);
        assertThat(jdbc.queryForObject("SELECT entity_type FROM audit_logs WHERE action='SERVICE_CREATED' AND entity_id=?",
                String.class, id)).isEqualTo("SERVICE");
    }

    @Test
    void allowsUnassignedOwnerAndBlankOrOmittedDescription() {
        var minimal = request(HttpMethod.POST, ROOT, adminToken, Map.of("code", "minimal", "name", "Minimal"));
        assertThat(minimal.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(minimal.getBody().get("ownerId").isNull()).isTrue();
        assertThat(minimal.getBody().get("owner").isNull()).isTrue();
        assertThat(minimal.getBody().get("description").isNull()).isTrue();
        Map<String, Object> payload = body("blank", null);
        payload.put("description", "  \n  ");
        assertThat(request(HttpMethod.POST, ROOT, adminToken, payload).getBody().get("description").isNull()).isTrue();
    }

    @Test
    void allThreeRolesReadServicesButOnlyAdministratorsWrite() {
        var service = create("permissions", developerId);
        for (String token : List.of(developerToken, approverToken)) {
            assertThat(request(HttpMethod.GET, ROOT, token, null).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(request(HttpMethod.GET, path(service), token, null).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(request(HttpMethod.POST, ROOT, token, body("forbidden", null)).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(request(HttpMethod.PUT, path(service), token, body("forbidden", null)).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(request(HttpMethod.PATCH, path(service) + "/status", token, Map.of("enabled", false)).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM software_services", Long.class)).isEqualTo(1);
        assertThat(serviceAuditCount()).isEqualTo(1);
    }

    @Test
    void anonymousRequestsToAllFiveEndpointsAreRejected() {
        var service = create("anonymous", null);
        assertThat(request(HttpMethod.GET, ROOT, null, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(request(HttpMethod.GET, path(service), null, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(request(HttpMethod.POST, ROOT, null, body("noauth", null)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(request(HttpMethod.PUT, path(service), null, body("noauth", null)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(request(HttpMethod.PATCH, path(service) + "/status", null, Map.of("enabled", false)).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        var invalidToken = request(HttpMethod.GET, ROOT, "not-a-jwt", null);
        assertThat(invalidToken.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(invalidToken.getBody().get("code").asText()).isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void listsWithStableDatabasePaginationAndStatusFilter() {
        create("first", null);
        var second = create("second", developerId);
        create("third", null);
        request(HttpMethod.PATCH, path(second) + "/status", adminToken, Map.of("enabled", false));
        var page = request(HttpMethod.GET, ROOT + "?page=1&size=1", developerToken, null).getBody();
        assertThat(page.get("items").size()).isEqualTo(1);
        assertThat(page.get("items").get(0).get("code").asText()).isEqualTo("second");
        assertThat(page.get("totalElements").asLong()).isEqualTo(3);
        assertThat(page.get("totalPages").asInt()).isEqualTo(3);
        assertThat(page.get("page").asInt()).isEqualTo(1);
        assertThat(page.get("size").asInt()).isEqualTo(1);
        var enabled = request(HttpMethod.GET, ROOT + "?enabled=true&size=1", approverToken, null).getBody();
        assertThat(enabled.get("totalElements").asLong()).isEqualTo(2);
        assertThat(enabled.get("totalPages").asInt()).isEqualTo(2);
        var disabled = request(HttpMethod.GET, ROOT + "?enabled=false", approverToken, null).getBody();
        assertThat(disabled.get("totalElements").asLong()).isEqualTo(1);
        assertThat(disabled.get("items").get(0).get("id")).isEqualTo(second.get("id"));
        assertThat(request(HttpMethod.GET, ROOT + "?page=99", adminToken, null).getBody().get("items").isEmpty()).isTrue();
    }

    @Test
    void updatesAllDetailsWhilePreservingStatusCreatorAndCreationTime() {
        var original = create("old-code", developerId);
        request(HttpMethod.PATCH, path(original) + "/status", adminToken, Map.of("enabled", false));
        var response = request(HttpMethod.PUT, path(original), adminToken,
                Map.of("code", "NEW-CODE", "name", " 新名称 "));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var changed = response.getBody();
        assertThat(changed.get("code").asText()).isEqualTo("new-code");
        assertThat(changed.get("name").asText()).isEqualTo("新名称");
        assertThat(changed.get("description").isNull()).isTrue();
        assertThat(changed.get("owner").isNull()).isTrue();
        assertThat(changed.get("ownerId").isNull()).isTrue();
        assertThat(changed.get("enabled").asBoolean()).isFalse();
        assertThat(changed.get("createdBy")).isEqualTo(original.get("createdBy"));
        assertThat(changed.get("createdAt")).isEqualTo(original.get("createdAt"));
        assertThat(changed.get("version").asLong()).isEqualTo(2);
        var retainedCode = request(HttpMethod.PUT, path(original), adminToken, body("NEW-CODE", adminId));
        assertThat(retainedCode.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retainedCode.getBody().get("ownerId").asLong()).isEqualTo(adminId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE entity_type='SERVICE' AND action='SERVICE_UPDATED'",
                Long.class)).isEqualTo(2);
    }

    @Test
    void togglesWithoutDeletingAndRecordsStatusHistoryInAudit() {
        var service = create("toggle", null);
        String statusPath = path(service) + "/status";
        for (boolean enabled : List.of(false, false, true)) {
            var changed = request(HttpMethod.PATCH, statusPath, adminToken, Map.of("enabled", enabled));
            assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(changed.getBody().get("enabled").asBoolean()).isEqualTo(enabled);
            assertThat(request(HttpMethod.GET, path(service), developerToken, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM software_services", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action='SERVICE_STATUS_UPDATED'", Long.class))
                .isEqualTo(3);
        assertThat(request(HttpMethod.DELETE, path(service), adminToken, null).getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }

    @Test
    void rejectsCaseInsensitiveDuplicateCodesOnCreateAndUpdateEvenForDisabledServices() {
        var first = create("unique-code", null);
        var second = create("another-code", null);
        request(HttpMethod.PATCH, path(first) + "/status", adminToken, Map.of("enabled", false));
        var duplicate = request(HttpMethod.POST, ROOT, adminToken, body("UNIQUE-CODE", null));
        assertError(duplicate, HttpStatus.CONFLICT, "SERVICE_CODE_EXISTS");
        assertError(request(HttpMethod.PUT, path(second), adminToken, body("UNIQUE-CODE", null)),
                HttpStatus.CONFLICT, "SERVICE_CODE_EXISTS");
        assertThat(request(HttpMethod.GET, path(second), adminToken, null).getBody().get("code").asText()).isEqualTo("another-code");
        assertThat(serviceAuditCount()).isEqualTo(3);
    }

    @Test
    void rejectsMissingOrDisabledOwnersWithoutPartialWrites() {
        assertError(request(HttpMethod.POST, ROOT, adminToken, body("missing-owner", Long.MAX_VALUE)),
                HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
        var service = create("valid", null);
        users.setEnabled(developerId, false, adminId);
        assertError(request(HttpMethod.POST, ROOT, adminToken, body("disabled-owner", developerId)),
                HttpStatus.BAD_REQUEST, "SERVICE_OWNER_DISABLED");
        assertError(request(HttpMethod.PUT, path(service), adminToken, body("changed", developerId)),
                HttpStatus.BAD_REQUEST, "SERVICE_OWNER_DISABLED");
        assertError(request(HttpMethod.PUT, path(service), adminToken, body("changed", Long.MAX_VALUE)),
                HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
        assertThat(request(HttpMethod.GET, path(service), adminToken, null).getBody().get("code").asText()).isEqualTo("valid");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM software_services", Long.class)).isEqualTo(1);
        assertThat(serviceAuditCount()).isEqualTo(1);
    }

    @Test
    void retainsDisabledOwnerHistoryButRequiresReassignmentBeforeEnabling() {
        var service = create("owner-history", developerId);
        users.setEnabled(developerId, false, adminId);
        var current = request(HttpMethod.GET, path(service), approverToken, null).getBody();
        assertThat(current.get("ownerId").asLong()).isEqualTo(developerId);
        assertThat(current.get("owner").get("enabled").asBoolean()).isFalse();
        assertThat(request(HttpMethod.PATCH, path(service) + "/status", adminToken, Map.of("enabled", false)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertError(request(HttpMethod.PATCH, path(service) + "/status", adminToken, Map.of("enabled", true)),
                HttpStatus.BAD_REQUEST, "SERVICE_OWNER_DISABLED");
        request(HttpMethod.PUT, path(service), adminToken, body("owner-history", null));
        assertThat(request(HttpMethod.PATCH, path(service) + "/status", adminToken, Map.of("enabled", true)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void validatesCreateAndUpdateFieldsAndLengths() {
        var service = create("validation", null);
        List<Map.Entry<String, Object>> invalid = List.of(
                Map.entry("code", ""), Map.entry("code", "  "), Map.entry("code", "bad code"),
                Map.entry("code", "-bad"), Map.entry("code", "x".repeat(51)), Map.entry("code", "支付"),
                Map.entry("name", "\u2003"), Map.entry("name", " "), Map.entry("name", "x".repeat(101)),
                Map.entry("description", "x".repeat(10001)), Map.entry("ownerId", 0), Map.entry("ownerId", -1));
        for (var field : invalid) {
            var payload = body("new-valid-code", null);
            payload.put(field.getKey(), field.getValue());
            assertThat(request(HttpMethod.POST, ROOT, adminToken, payload).getStatusCode())
                    .as("POST %s", field.getKey()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(request(HttpMethod.PUT, path(service), adminToken, payload).getStatusCode())
                    .as("PUT %s", field.getKey()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        for (String field : List.of("code", "name")) {
            var payload = body("new-valid-code", null);
            payload.remove(field);
            assertThat(request(HttpMethod.POST, ROOT, adminToken, payload).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        var boundary = body("a".repeat(50), null);
        boundary.put("name", "名".repeat(100));
        boundary.put("description", "d".repeat(10000));
        assertThat(request(HttpMethod.POST, ROOT, adminToken, boundary).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(request(HttpMethod.POST, ROOT, adminToken, Map.of("code", "a", "name", "Single"))
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void validatesPathPaginationAndStatusParameters() {
        var service = create("parameters", null);
        for (String query : List.of("page=-1", "size=0", "size=101", "page=wrong", "enabled=wrong")) {
            assertThat(request(HttpMethod.GET, ROOT + "?" + query, adminToken, null).getStatusCode())
                    .as(query).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        for (String id : List.of("0", "-1", "wrong", "9223372036854775808")) {
            assertThat(request(HttpMethod.GET, ROOT + "/" + id, adminToken, null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(request(HttpMethod.PUT, ROOT + "/" + id, adminToken, body("parameters", null)).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(request(HttpMethod.PATCH, ROOT + "/" + id + "/status", adminToken, Map.of("enabled", true)).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }
        var nullStatus = new HashMap<String, Object>();
        nullStatus.put("enabled", null);
        for (Object payload : List.of(Map.of(), nullStatus, Map.of("enabled", "wrong"))) {
            assertThat(request(HttpMethod.PATCH, path(service) + "/status", adminToken, payload).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Test
    void returnsNotFoundForMissingServiceOnReadAndWrites() {
        String missing = ROOT + "/" + Long.MAX_VALUE;
        assertError(request(HttpMethod.GET, missing, developerToken, null), HttpStatus.NOT_FOUND, "SERVICE_NOT_FOUND");
        assertError(request(HttpMethod.PUT, missing, adminToken, body("missing", null)), HttpStatus.NOT_FOUND, "SERVICE_NOT_FOUND");
        assertError(request(HttpMethod.PATCH, missing + "/status", adminToken, Map.of("enabled", false)),
                HttpStatus.NOT_FOUND, "SERVICE_NOT_FOUND");
        assertThat(serviceAuditCount()).isZero();
    }

    @Test
    void reflectsRoleRevocationAndAccountDisableOnExistingTokens() {
        long secondAdmin = users.create(new CreateUserRequest("secondadmin", "secondadmin@example.test", "Second admin",
                PASSWORD, java.util.Set.of(RoleCode.ADMINISTRATOR)), adminId).id();
        String token = login("secondadmin");
        users.replaceRoles(secondAdmin, java.util.Set.of(RoleCode.DEVELOPER), adminId);
        assertThat(request(HttpMethod.POST, ROOT, token, body("revoked", null)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(request(HttpMethod.GET, ROOT, token, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        users.setEnabled(secondAdmin, false, adminId);
        assertThat(request(HttpMethod.GET, ROOT, token, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rollsBackFlushedDetailsIfTheTransactionLaterFails() {
        var service = create("rollback-original", developerId);
        assertThatThrownBy(() -> services.update(service.get("id").asLong(),
                new SaveServiceRequest("rollback-changed", "Changed", null, null), Long.MAX_VALUE))
                .isInstanceOf(ApiException.class);
        var current = request(HttpMethod.GET, path(service), adminToken, null).getBody();
        assertThat(current.get("code").asText()).isEqualTo("rollback-original");
        assertThat(current.get("ownerId").asLong()).isEqualTo(developerId);
        assertThat(current.get("version").asLong()).isZero();
        assertThat(serviceAuditCount()).isEqualTo(1);
    }

    private String login(String username) {
        var response = request(HttpMethod.POST, "/api/v1/auth/login", null, Map.of("username", username, "password", PASSWORD));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody().get("accessToken").asText();
    }

    private Map<String, Object> body(String code, Long ownerId) {
        var result = new HashMap<String, Object>();
        result.put("code", code);
        result.put("name", "Test Service");
        result.put("description", "Test description");
        result.put("ownerId", ownerId);
        return result;
    }

    private JsonNode create(String code, Long ownerId) {
        var response = request(HttpMethod.POST, ROOT, adminToken, body(code, ownerId));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private String path(JsonNode service) { return ROOT + "/" + service.get("id").asLong(); }

    private long serviceAuditCount() {
        return jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE entity_type='SERVICE'", Long.class);
    }

    private void assertError(ResponseEntity<JsonNode> response, HttpStatus status, String code) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody().get("code").asText()).isEqualTo(code);
    }

    private ResponseEntity<JsonNode> request(HttpMethod method, String path, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");
        if (token != null) { headers.setBearerAuth(token); }
        return http.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }
}
