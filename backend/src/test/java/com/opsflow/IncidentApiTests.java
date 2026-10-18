package com.opsflow;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.fasterxml.jackson.databind.JsonNode;
import com.opsflow.services.ServiceDtos.SaveServiceRequest;
import com.opsflow.services.SoftwareServiceManager;
import com.opsflow.users.*;
import com.opsflow.users.UserDtos.CreateUserRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IncidentApiTests {
    private static final String ROOT = "/api/v1/incidents";
    private static final String PASSWORD = "IncidentTestPass123!";
    @Autowired private TestRestTemplate http;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RoleRepository roles;
    @Autowired private UserRepository accounts;
    @Autowired private UserService users;
    @Autowired private SoftwareServiceManager services;
    private long adminId, developerId, otherId, approverId, serviceId, disabledServiceId;
    private String admin, developer, other, approver;

    @BeforeEach
    void fixtures() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) { assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:"); }
        H2TestData.reset(jdbc);
        for (RoleCode code : RoleCode.values()) roles.saveAndFlush(new Role(code, code.name()));
        users.bootstrapAdministrator("incadmin", "incadmin@example.test", "Admin", PASSWORD);
        adminId = accounts.findByUsernameIgnoreCase("incadmin").orElseThrow().getId();
        developerId = createUser("incdeveloper", RoleCode.DEVELOPER);
        otherId = createUser("incother", RoleCode.DEVELOPER);
        approverId = createUser("incapprover", RoleCode.APPROVER);
        admin = login("incadmin"); developer = login("incdeveloper"); other = login("incother"); approver = login("incapprover");
        serviceId = services.create(new SaveServiceRequest("incident-service", "Incident service", null, null), adminId).id();
        disabledServiceId = services.create(new SaveServiceRequest("disabled-service", "Disabled service", null, null), adminId).id();
        services.setEnabled(disabledServiceId, false, adminId);
    }

    @Test
    void createsUsingActorAndInitialStateAndHistoryWithoutLeakingPrivateUserData() {
        var payload = body(); payload.put("reporterId", adminId); payload.put("status", "CLOSED"); payload.put("sourceChangeId", 999);
        var response = call(HttpMethod.POST, ROOT, developer, payload);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var incident = response.getBody();
        assertThat(incident.get("reporter").get("id").asLong()).isEqualTo(developerId);
        assertThat(incident.get("status").asText()).isEqualTo("OPEN");
        assertThat(incident.get("assignee").isNull()).isTrue();
        assertThat(incident.get("sourceChangeId").isNull()).isTrue();
        assertThat(incident.get("resolution").isNull()).isTrue();
        assertThat(incident.get("createdAt").isNull()).isFalse();
        assertThat(incident.toString()).doesNotContain("password", "email", "roleAssignments");
        var history = call(HttpMethod.GET, path(incident) + "/history", approver, null).getBody();
        assertThat(history.get("items").get(0).get("oldStatus").isNull()).isTrue();
        assertThat(history.get("items").get(0).get("newStatus").asText()).isEqualTo("OPEN");
        assertThat(audits()).isEqualTo(1);
    }

    @Test
    void completeLifecycleAddsNoteAndPreservesResolutionAndTimestamps() {
        var incident = create(admin);
        incident = assign(incident, developerId);
        incident = transition(incident, developer, "INVESTIGATING", null);
        var note = call(HttpMethod.POST, path(incident) + "/notes", developer, Map.of("content", "  Investigating logs  ", "version", version(incident)));
        assertThat(note.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(note.getBody().get("content").asText()).isEqualTo("Investigating logs");
        incident = get(incident, developer);
        assertThat(version(incident)).isEqualTo(3);
        incident = transition(incident, developer, "RESOLVED", "  Connection pool repaired  ");
        assertThat(incident.get("resolution").asText()).isEqualTo("Connection pool repaired");
        assertThat(incident.get("resolvedAt").isNull()).isFalse();
        String resolvedAt = incident.get("resolvedAt").asText();
        incident = transition(incident, admin, "CLOSED", null);
        assertThat(incident.get("closedAt").isNull()).isFalse();
        assertThat(incident.get("resolvedAt").asText()).isEqualTo(resolvedAt);
        assertThat(incident.get("assignee").get("id").asLong()).isEqualTo(developerId);
        assertThat(incident.get("permissions").get("canAddNote").asBoolean()).isFalse();
        assertThat(call(HttpMethod.GET, path(incident) + "/history", approver, null).getBody().get("totalElements").asLong()).isEqualTo(5);
        assertThat(audits()).isEqualTo(6);
    }

    @Test
    void approverReadsButCannotCreateAssignTransitionNoteOrReadAssigneeDirectory() {
        var incident = create(admin);
        for (String path : List.of(ROOT, path(incident), path(incident) + "/notes", path(incident) + "/history"))
            assertThat(call(HttpMethod.GET, path, approver, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.POST, ROOT, approver, body()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.POST, path(incident) + "/assignment", approver, Map.of("assigneeId", developerId, "version", 0)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.POST, path(incident) + "/transitions", approver, Map.of("status", "INVESTIGATING", "version", 0)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.POST, path(incident) + "/notes", approver, Map.of("content", "Forbidden", "version", 0)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.GET, ROOT + "/assignees", approver, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(audits()).isEqualTo(1);
    }

    @Test
    void unauthenticatedRequestsToEveryEndpointAreRejected() {
        var incident = create(admin);
        for (String path : List.of(ROOT, path(incident), ROOT + "/assignees", path(incident) + "/notes", path(incident) + "/history"))
            assertThat(call(HttpMethod.GET, path, null, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        for (String path : List.of(ROOT, path(incident) + "/assignment", path(incident) + "/transitions", path(incident) + "/notes"))
            assertThat(call(HttpMethod.POST, path, null, body()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void onlyCurrentAssigneeOrAdminCanWorkAndOnlyAdminCanAssignOrClose() {
        var incident = assign(create(developer), developerId);
        assertThat(call(HttpMethod.POST, path(incident) + "/transitions", other, Map.of("status", "INVESTIGATING", "version", version(incident))).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.POST, path(incident) + "/notes", other, Map.of("content", "Other", "version", version(incident))).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.POST, path(incident) + "/assignment", developer, Map.of("assigneeId", developerId, "version", version(incident))).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        incident = transition(incident, admin, "INVESTIGATING", null);
        incident = transition(incident, admin, "RESOLVED", "Fixed by administrator");
        assertThat(call(HttpMethod.POST, path(incident) + "/transitions", developer, Map.of("status", "CLOSED", "version", version(incident))).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        transition(incident, admin, "CLOSED", null);
    }

    @Test
    void validatesMissingDisabledAndWrongRoleAssigneesAndFiltersCandidatePagination() {
        var incident = create(admin);
        users.setEnabled(otherId, false, adminId);
        for (long id : List.of(otherId, approverId)) assertError(call(HttpMethod.POST, path(incident) + "/assignment", admin,
                Map.of("assigneeId", id, "version", 0)), 400, "INVALID_ASSIGNEE");
        assertError(call(HttpMethod.POST, path(incident) + "/assignment", admin, Map.of("assigneeId", Long.MAX_VALUE, "version", 0)), 404, "USER_NOT_FOUND");
        var candidates = call(HttpMethod.GET, ROOT + "/assignees?size=1", admin, null).getBody();
        assertThat(candidates.get("totalElements").asLong()).isEqualTo(2);
        assertThat(candidates.get("totalPages").asInt()).isEqualTo(2);
        assertThat(candidates.toString()).doesNotContain("password", "email", "roles");
        assertThat(call(HttpMethod.GET, ROOT + "/assignees?page=1&size=1", admin, null).getBody().get("items").get(0).get("id").asLong()).isEqualTo(developerId);
    }

    @Test
    void rejectsInactiveOrMissingServiceButKeepsExistingIncidentWorkableAfterServiceDisable() {
        var payload = body(); payload.put("serviceId", disabledServiceId);
        assertError(call(HttpMethod.POST, ROOT, admin, payload), 400, "INCIDENT_SERVICE_DISABLED");
        payload.put("serviceId", Long.MAX_VALUE);
        assertError(call(HttpMethod.POST, ROOT, admin, payload), 404, "SERVICE_NOT_FOUND");
        var incident = create(admin);
        services.setEnabled(serviceId, false, adminId);
        incident = assign(incident, developerId);
        transition(incident, developer, "INVESTIGATING", null);
    }

    @Test
    void rejectsSkippedRepeatedBackwardTransitionsAndRequiresResolution() {
        var incident = create(admin);
        for (String target : List.of("OPEN", "ASSIGNED", "INVESTIGATING", "RESOLVED", "CLOSED")) assertError(call(HttpMethod.POST,
                path(incident) + "/transitions", admin, Map.of("status", target, "version", 0)), 409, "INVALID_INCIDENT_TRANSITION");
        incident = assign(incident, developerId);
        assertError(call(HttpMethod.POST, path(incident) + "/transitions", admin, Map.of("status", "INVESTIGATING", "version", version(incident), "resolution", "Wrong place")), 400, "UNEXPECTED_RESOLUTION");
        incident = transition(incident, developer, "INVESTIGATING", null);
        assertError(call(HttpMethod.POST, path(incident) + "/transitions", developer, Map.of("status", "RESOLVED", "version", version(incident), "resolution", " \u2003 ")), 400, "RESOLUTION_REQUIRED");
        assertError(call(HttpMethod.POST, path(incident) + "/transitions", developer, Map.of("status", "ASSIGNED", "version", version(incident))), 409, "INVALID_INCIDENT_TRANSITION");
        assertThat(audits()).isEqualTo(3);
    }

    @Test
    void staleVersionsCannotAssignTransitionOrAppendNoteAndCauseNoExtraHistory() {
        var original = create(admin);
        var incident = assign(original, developerId);
        assertError(call(HttpMethod.POST, path(incident) + "/assignment", admin, Map.of("assigneeId", otherId, "version", 0)), 409, "CONCURRENT_UPDATE");
        assertError(call(HttpMethod.POST, path(incident) + "/transitions", developer, Map.of("status", "INVESTIGATING", "version", 0)), 409, "CONCURRENT_UPDATE");
        assertError(call(HttpMethod.POST, path(incident) + "/notes", developer, Map.of("content", "Stale", "version", 0)), 409, "CONCURRENT_UPDATE");
        assertThat(call(HttpMethod.GET, path(incident) + "/notes", admin, null).getBody().get("totalElements").asLong()).isZero();
        assertThat(audits()).isEqualTo(2);
    }

    @Test
    void reassignmentKeepsInvestigatingStateAndRevokesOldAssigneeAccess() {
        var incident = transition(assign(create(admin), developerId), developer, "INVESTIGATING", null);
        incident = assign(incident, otherId);
        assertThat(incident.get("status").asText()).isEqualTo("INVESTIGATING");
        assertThat(get(incident, developer).get("permissions").get("canResolve").asBoolean()).isFalse();
        assertThat(get(incident, other).get("permissions").get("canResolve").asBoolean()).isTrue();
        assertThat(call(HttpMethod.POST, path(incident) + "/notes", developer, Map.of("content", "No longer assigned", "version", version(incident))).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        var repeated = assign(incident, otherId);
        assertThat(version(repeated)).isEqualTo(version(incident));
        assertThat(call(HttpMethod.GET, path(incident) + "/history", admin, null).getBody().get("totalElements").asLong()).isEqualTo(3);
        incident = transition(incident, other, "RESOLVED", "Fixed by new assignee");
        assertError(call(HttpMethod.POST, path(incident) + "/assignment", admin, Map.of("assigneeId", developerId, "version", version(incident))), 409, "INCIDENT_NOT_ASSIGNABLE");
        assertError(call(HttpMethod.POST, path(incident) + "/notes", admin, Map.of("content", "Late note", "version", version(incident))), 409, "INCIDENT_NOT_WRITABLE");
    }

    @Test
    void listFiltersAreAppliedInDatabaseAndReturnCorrectCountsAndOrder() {
        create(admin);
        var payload = body(); payload.put("severity", "CRITICAL");
        var second = call(HttpMethod.POST, ROOT, developer, payload).getBody();
        assign(second, developerId);
        var all = call(HttpMethod.GET, ROOT + "?size=1", approver, null).getBody();
        assertThat(all.get("totalElements").asLong()).isEqualTo(2);
        assertThat(all.get("items").get(0).get("id")).isEqualTo(second.get("id"));
        var filtered = call(HttpMethod.GET, ROOT + "?status=ASSIGNED&severity=CRITICAL&serviceId=" + serviceId + "&assigneeId=" + developerId, approver, null).getBody();
        assertThat(filtered.get("totalElements").asLong()).isEqualTo(1);
        assertThat(call(HttpMethod.GET, ROOT + "?status=CLOSED", admin, null).getBody().get("items").isEmpty()).isTrue();
    }

    @Test
    void notesAndHistoryPaginateAndRetainAuthorAndActor() {
        var incident = assign(create(admin), developerId);
        for (String content : List.of("First note", "Second note")) {
            assertThat(call(HttpMethod.POST, path(incident) + "/notes", developer, Map.of("content", content, "version", version(incident))).getStatusCode()).isEqualTo(HttpStatus.CREATED);
            incident = get(incident, admin);
        }
        var notes = call(HttpMethod.GET, path(incident) + "/notes?size=1", approver, null).getBody();
        assertThat(notes.get("totalElements").asLong()).isEqualTo(2);
        assertThat(notes.get("items").get(0).get("content").asText()).isEqualTo("Second note");
        assertThat(notes.get("items").get(0).get("author").get("id").asLong()).isEqualTo(developerId);
        assertThat(call(HttpMethod.GET, path(incident) + "/history?size=1", other, null).getBody().get("items").get(0).get("changedBy").get("id").asLong()).isEqualTo(adminId);
    }

    @Test
    void validatesRequiredFieldsEnumsLengthsIdsAndPageBounds() {
        for (var entry : List.<Map.Entry<String,Object>>of(Map.entry("title", "\u2003"), Map.entry("title", "x".repeat(201)),
                Map.entry("description", " "), Map.entry("description", "x".repeat(10001)), Map.entry("severity", "UNKNOWN"), Map.entry("serviceId", 0))) {
            var payload = body(); payload.put(entry.getKey(), entry.getValue());
            assertThat(call(HttpMethod.POST, ROOT, admin, payload).getStatusCode()).as(entry.getKey()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        for (String field : List.of("title", "description", "severity", "serviceId")) { var payload = body(); payload.remove(field); assertThat(call(HttpMethod.POST, ROOT, admin, payload).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST); }
        for (String query : List.of("page=-1", "size=0", "size=101", "status=UNKNOWN", "severity=UNKNOWN", "serviceId=0", "assigneeId=-1"))
            assertThat(call(HttpMethod.GET, ROOT + "?" + query, admin, null).getStatusCode()).as(query).isEqualTo(HttpStatus.BAD_REQUEST);
        var incident = create(admin);
        for (String suffix : List.of("/assignment", "/transitions", "/notes")) assertThat(call(HttpMethod.POST, path(incident) + suffix, admin, Map.of()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(HttpMethod.POST, path(incident) + "/notes", admin, Map.of("content", "\u2003", "version", 0)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(HttpMethod.GET, ROOT + "/-1", admin, null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void missingIncidentsReturn404OnEveryOperationAndDeleteIsNotSupported() {
        String missing = ROOT + "/" + Long.MAX_VALUE;
        for (String suffix : List.of("", "/notes", "/history")) assertError(call(HttpMethod.GET, missing + suffix, admin, null), 404, "INCIDENT_NOT_FOUND");
        assertError(call(HttpMethod.POST, missing + "/assignment", admin, Map.of("assigneeId", developerId, "version", 0)), 404, "INCIDENT_NOT_FOUND");
        assertError(call(HttpMethod.POST, missing + "/transitions", admin, Map.of("status", "INVESTIGATING", "version", 0)), 404, "INCIDENT_NOT_FOUND");
        assertError(call(HttpMethod.POST, missing + "/notes", admin, Map.of("content", "Missing", "version", 0)), 404, "INCIDENT_NOT_FOUND");
        assertThat(call(HttpMethod.DELETE, path(create(admin)), admin, null).getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }

    @Test
    void disabledOrDemotedAssigneeCannotContinueUsingOldToken() {
        var incident = assign(create(admin), developerId);
        users.replaceRoles(developerId, Set.of(RoleCode.APPROVER), adminId);
        assertThat(get(incident, developer).get("permissions").get("canInvestigate").asBoolean()).isFalse();
        assertThat(call(HttpMethod.POST, path(incident) + "/transitions", developer, Map.of("status", "INVESTIGATING", "version", version(incident))).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        users.setEnabled(developerId, false, adminId);
        assertThat(call(HttpMethod.GET, path(incident), developer, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private long createUser(String username, RoleCode role) { return users.create(new CreateUserRequest(username, username + "@example.test", username, PASSWORD, Set.of(role)), adminId).id(); }
    private String login(String username) { return call(HttpMethod.POST, "/api/v1/auth/login", null, Map.of("username", username, "password", PASSWORD)).getBody().get("accessToken").asText(); }
    private Map<String, Object> body() { return new HashMap<>(Map.of("serviceId", serviceId, "title", " Payment timeout ", "description", " Requests are failing ", "severity", "HIGH")); }
    private JsonNode create(String token) { var result = call(HttpMethod.POST, ROOT, token, body()); assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED); return result.getBody(); }
    private JsonNode assign(JsonNode incident, long assigneeId) { var result = call(HttpMethod.POST, path(incident) + "/assignment", admin, Map.of("assigneeId", assigneeId, "version", version(incident))); assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK); return result.getBody(); }
    private JsonNode transition(JsonNode incident, String token, String status, String resolution) { var payload = new HashMap<String,Object>(Map.of("status", status, "version", version(incident))); if (resolution != null) payload.put("resolution", resolution); var result = call(HttpMethod.POST, path(incident) + "/transitions", token, payload); assertThat(result.getStatusCode()).as(result.getBody().toString()).isEqualTo(HttpStatus.OK); return result.getBody(); }
    private JsonNode get(JsonNode incident, String token) { return call(HttpMethod.GET, path(incident), token, null).getBody(); }
    private long version(JsonNode incident) { return incident.get("version").asLong(); }
    private String path(JsonNode incident) { return ROOT + "/" + incident.get("id").asLong(); }
    private long audits() { return jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE entity_type='INCIDENT'", Long.class); }
    private void assertError(ResponseEntity<JsonNode> response, int status, String code) { assertThat(response.getStatusCode().value()).as(String.valueOf(response.getBody())).isEqualTo(status); assertThat(response.getBody().get("code").asText()).isEqualTo(code); }
    private ResponseEntity<JsonNode> call(HttpMethod method, String path, String token, Object body) { var headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON); if (token != null) headers.setBearerAuth(token); return http.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class); }
}
