package com.opsflow;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.opsflow.changes.ChangeStatus;
import com.opsflow.incidents.IncidentService;
import com.opsflow.incidents.IncidentSeverity;
import com.opsflow.incidents.IncidentDtos.CreateIncidentRequest;
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

@ActiveProfiles("test") @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChangeDashboardApiTests {
    private static final String ROOT="/api/v1/changes", PASSWORD="ChangeTestPassword123!";
    @Autowired private TestRestTemplate http;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RoleRepository roles;
    @Autowired private UserRepository accounts;
    @Autowired private UserService users;
    @Autowired private SoftwareServiceManager services;
    @Autowired private IncidentService incidents;
    private long adminId, ownerId, otherId, approverId, serviceId, secondServiceId;
    private String admin, owner, other, approver;
    @BeforeEach void fixtures() {
        H2TestData.reset(jdbc);
        for(var code:RoleCode.values()) roles.saveAndFlush(new Role(code,code.name()));
        users.bootstrapAdministrator("chgadmin","chgadmin@example.test","Admin",PASSWORD);
        adminId=accounts.findByUsernameIgnoreCase("chgadmin").orElseThrow().getId();
        ownerId=user("chgowner",RoleCode.DEVELOPER); otherId=user("chgother",RoleCode.DEVELOPER); approverId=user("chgapprover",RoleCode.APPROVER);
        admin=login("chgadmin"); owner=login("chgowner"); other=login("chgother"); approver=login("chgapprover");
        serviceId=services.create(new SaveServiceRequest("change-main","Main service",null,null),adminId).id();
        secondServiceId=services.create(new SaveServiceRequest("change-other","Other service",null,null),adminId).id();
    }
    @Test void createsMinimalDraftUsingCurrentActorAndSafeSummaries() {
        var draft=body(false); draft.put("requesterId",adminId); draft.put("status","APPROVED");
        var response=call(HttpMethod.POST,ROOT,owner,draft); assertThat(response.getStatusCode().value()).isEqualTo(201);
        var change=response.getBody(); assertThat(change.get("status").asText()).isEqualTo("DRAFT");
        assertThat(change.get("requester").get("id").asLong()).isEqualTo(ownerId);
        assertThat(change.get("businessReason").isNull()).isTrue(); assertThat(change.get("failureIncidentId").isNull()).isTrue();
        assertThat(change.toString()).doesNotContain("password","email");
        assertThat(call(HttpMethod.GET,path(change)+"/history",approver,null).getBody().get("totalElements").asInt()).isEqualTo(1);
    }
    @Test void approvedChangeCanStartAndCompleteWithOrderedTimesAndReviewHistory() {
        var change=submit(create(true)); change=review(change,"APPROVED","Looks safe"); change=start(change);
        change=finish(change,"COMPLETED","Verified service recovery");
        assertThat(change.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(change.get("implementationResult").asText()).isEqualTo("Verified service recovery");
        assertThat(change.get("failureIncidentId").isNull()).isTrue();
        assertThat(Instant.parse(change.get("implementationFinishedAt").asText())).isAfterOrEqualTo(Instant.parse(change.get("implementationStartedAt").asText()));
        assertThat(call(HttpMethod.GET,path(change)+"/reviews",other,null).getBody().get("items").get(0).get("reviewer").get("id").asLong()).isEqualTo(approverId);
        assertThat(call(HttpMethod.GET,path(change)+"/history",owner,null).getBody().get("totalElements").asInt()).isEqualTo(5);
    }
    @Test void failedImplementationCreatesExactlyOneSameServiceIncidentAndRejectsRepeatedResult() {
        var change=start(review(submit(create(true)),"APPROVED",null));
        services.setEnabled(serviceId,false,adminId); // Results must remain recordable even if service is disabled mid-implementation.
        change=finish(change,"FAILED","Deployment failed; rollback completed");
        long incidentId=change.get("failureIncidentId").asLong();
        var incident=call(HttpMethod.GET,"/api/v1/incidents/"+incidentId,owner,null).getBody();
        assertThat(incident.get("sourceChangeId").asLong()).isEqualTo(change.get("id").asLong());
        assertThat(incident.get("service").get("id").asLong()).isEqualTo(serviceId);
        assertThat(incident.get("status").asText()).isEqualTo("OPEN"); assertThat(incident.get("severity").asText()).isEqualTo("HIGH");
        error(call(HttpMethod.POST,path(change)+"/implementation/result",owner,Map.of("version",version(change),"outcome","FAILED","result","Again")),409,"INVALID_CHANGE_STATE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM incidents WHERE source_change_id=?",Long.class,change.get("id").asLong())).isEqualTo(1);
    }
    @Test void rejectedRequestRequiresReasonAndEditingStartsANewSubmissionRound() {
        var change=submit(create(true));
        error(call(HttpMethod.POST,path(change)+"/reviews",approver,Map.of("version",version(change),"decision","REJECTED","comment","\u2003")),400,"REJECTION_COMMENT_REQUIRED");
        change=review(change,"REJECTED","Add rollback verification");
        var update=body(true); update.put("version",version(change)); update.put("rollbackPlan","Rollback plus verification");
        change=ok(call(HttpMethod.PUT,path(change),owner,update)); assertThat(change.get("status").asText()).isEqualTo("DRAFT");
        change=submit(change); assertThat(change.get("submissionNumber").asInt()).isEqualTo(2);
        change=review(change,"APPROVED","Now sufficient");
        var reviews=call(HttpMethod.GET,path(change)+"/reviews?size=1",owner,null).getBody();
        assertThat(reviews.get("totalElements").asInt()).isEqualTo(2); assertThat(reviews.get("totalPages").asInt()).isEqualTo(2);
        assertThat(reviews.get("items").get(0).get("submissionNumber").asInt()).isEqualTo(2);
    }
    @Test void submissionRequiresCompletePlansAndAFuturePlannedTime() {
        var change=create(false);
        error(call(HttpMethod.POST,path(change)+"/submit",owner,Map.of("version",0)),400,"CHANGE_NOT_READY");
        var update=body(true); update.put("plannedImplementationAt",Instant.now().minusSeconds(60).toString()); update.put("version",0);
        change=ok(call(HttpMethod.PUT,path(change),owner,update));
        error(call(HttpMethod.POST,path(change)+"/submit",owner,Map.of("version",version(change))),400,"INVALID_PLANNED_TIME");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM change_reviews",Long.class)).isZero();
    }
    @Test void enforcesOwnerWriterAndApproverPermissionsWithoutAdminBypass() {
        var change=create(true);
        assertThat(call(HttpMethod.POST,ROOT,approver,body(true)).getStatusCode().value()).isEqualTo(403);
        var edit=body(true); edit.put("version",0);
        for(String token:List.of(other,approver)) {
            assertThat(call(HttpMethod.PUT,path(change),token,edit).getStatusCode().value()).isEqualTo(403);
            assertThat(call(HttpMethod.POST,path(change)+"/submit",token,Map.of("version",0)).getStatusCode().value()).isEqualTo(403);
        }
        change=submit(change);
        for(String token:List.of(admin,owner,other)) assertThat(call(HttpMethod.POST,path(change)+"/reviews",token,Map.of("version",version(change),"decision","APPROVED")).getStatusCode().value()).isEqualTo(403);
        for(String token:List.of(admin,owner,other,approver)) assertThat(call(HttpMethod.GET,path(change),token,null).getStatusCode().value()).isEqualTo(200);
    }
    @Test void multiRoleRequesterStillCannotApproveOwnChange() {
        users.replaceRoles(ownerId,Set.of(RoleCode.DEVELOPER,RoleCode.APPROVER),adminId);
        var change=submit(create(true));
        error(call(HttpMethod.POST,path(change)+"/reviews",owner,Map.of("version",version(change),"decision","APPROVED")),403,"SELF_APPROVAL_FORBIDDEN");
        assertThat(change.get("permissions").get("canReview").asBoolean()).isFalse();
    }
    @Test void checksSameServiceIncidentAndEnabledServiceOnCreateSubmitAndStart() {
        var incident=incidents.create(new CreateIncidentRequest(secondServiceId,"Other incident","Other service",IncidentSeverity.HIGH),adminId);
        var payload=body(true); payload.put("relatedIncidentId",incident.id());
        error(call(HttpMethod.POST,ROOT,owner,payload),400,"RELATED_INCIDENT_SERVICE_MISMATCH");
        payload.put("relatedIncidentId",Long.MAX_VALUE); error(call(HttpMethod.POST,ROOT,owner,payload),404,"INCIDENT_NOT_FOUND");
        var change=create(true); services.setEnabled(serviceId,false,adminId);
        error(call(HttpMethod.POST,path(change)+"/submit",owner,Map.of("version",0)),400,"CHANGE_SERVICE_DISABLED");
        error(call(HttpMethod.POST,ROOT,owner,body(true)),400,"CHANGE_SERVICE_DISABLED");
        services.setEnabled(serviceId,true,adminId); change=review(submit(change),"APPROVED",null); services.setEnabled(serviceId,false,adminId);
        error(call(HttpMethod.POST,path(change)+"/implementation/start",owner,Map.of("version",version(change))),400,"CHANGE_SERVICE_DISABLED");
    }
    @Test void blocksSkippedStatesDuplicateReviewsAndStaleWrites() {
        var change=create(true);
        error(call(HttpMethod.POST,path(change)+"/implementation/start",owner,Map.of("version",0)),409,"INVALID_CHANGE_STATE");
        change=submit(change);
        var update=body(true); update.put("version",0); error(call(HttpMethod.PUT,path(change),owner,update),409,"CONCURRENT_UPDATE");
        error(call(HttpMethod.POST,path(change)+"/reviews",approver,Map.of("version",0,"decision","APPROVED")),409,"CONCURRENT_UPDATE");
        update.put("version",version(change)); error(call(HttpMethod.PUT,path(change),owner,update),409,"INVALID_CHANGE_STATE");
        change=review(change,"APPROVED",null);
        error(call(HttpMethod.POST,path(change)+"/reviews",approver,Map.of("version",version(change),"decision","APPROVED")),409,"INVALID_CHANGE_STATE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM change_reviews",Long.class)).isEqualTo(1);
    }
    @Test void filtersAndPaginationCountAllMatchingDatabaseRows() {
        create(false); var change=submit(create(true));
        var result=call(HttpMethod.GET,ROOT+"?status=PENDING_APPROVAL&riskLevel=HIGH&serviceId="+serviceId+"&requesterId="+ownerId+"&size=1",approver,null).getBody();
        assertThat(result.get("totalElements").asInt()).isEqualTo(1); assertThat(result.get("items").get(0).get("id")).isEqualTo(change.get("id"));
        assertThat(call(HttpMethod.GET,ROOT+"?size=1",owner,null).getBody().get("totalPages").asInt()).isEqualTo(2);
    }
    @Test void validatesFieldsVersionsEnumsAndPaging() {
        var change=create(false);
        for(var field:List.<Map.Entry<String,Object>>of(Map.entry("title","\u2003"),Map.entry("title","x".repeat(201)),Map.entry("riskLevel","BAD"),Map.entry("businessReason","x".repeat(10001)),Map.entry("plannedImplementationAt","not-a-date"),Map.entry("serviceId",0))) {
            var payload=body(true); payload.put(field.getKey(),field.getValue()); assertThat(call(HttpMethod.POST,ROOT,owner,payload).getStatusCode().value()).as(field.getKey()).isEqualTo(400);
        }
        error(call(HttpMethod.PUT,path(change),owner,body(false)),400,"VERSION_REQUIRED");
        for(String suffix:List.of("/submit","/reviews","/implementation/start","/implementation/result")) assertThat(call(HttpMethod.POST,path(change)+suffix,admin,Map.of()).getStatusCode().value()).isIn(400,403);
        for(String query:List.of("page=-1","size=101","riskLevel=BAD","status=BAD","serviceId=0","requesterId=-1")) assertThat(call(HttpMethod.GET,ROOT+"?"+query,owner,null).getStatusCode().value()).isEqualTo(400);
        assertThat(call(HttpMethod.POST,path(change)+"/implementation/result",owner,Map.of("version",0,"outcome","FAILED","result","\u2003")).getStatusCode().value()).isEqualTo(400);
    }
    @Test void anonymousMissingAndDeleteRequestsAreHandled() {
        var change=create(true);
        for(String suffix:List.of("","/reviews","/history")) {
            assertThat(call(HttpMethod.GET,path(change)+suffix,null,null).getStatusCode().value()).isEqualTo(401);
            error(call(HttpMethod.GET,ROOT+"/"+Long.MAX_VALUE+suffix,owner,null),404,"CHANGE_NOT_FOUND");
        }
        assertThat(call(HttpMethod.DELETE,path(change),admin,null).getStatusCode().value()).isEqualTo(405);
        assertThat(call(HttpMethod.GET,"/api/v1/dashboard/summary",null,null).getStatusCode().value()).isEqualTo(401);
    }
    @Test void dashboardHandlesEmptyDataAndZeroDenominator() {
        var summary=dashboard(approver);
        assertThat(summary.get("totalServices").asLong()).isEqualTo(2); assertThat(summary.get("totalIncidents").asLong()).isZero();
        assertThat(summary.get("totalChanges").asLong()).isZero(); assertThat(summary.get("changeSuccessRate").isNull()).isTrue();
        assertThat(summary.get("trend").size()).isEqualTo(7); assertThat(summary.get("zoneId").asText()).isEqualTo("UTC");
        assertThat(summary.get("changesByStatus").size()).isEqualTo(7);
        assertThat(summary.toString()).doesNotContain("password","email");
    }
    @Test void dashboardCountsSuccessfulAndFailedImplementationsAndLinkedIncidentTogether() {
        finish(start(review(submit(create(true)),"APPROVED",null)),"COMPLETED","Success");
        finish(start(review(submit(create(true)),"APPROVED",null)),"FAILED","Failure");
        create(false); submit(create(true));
        var summary=dashboard(owner);
        assertThat(summary.get("totalChanges").asInt()).isEqualTo(4); assertThat(summary.get("pendingApprovals").asInt()).isEqualTo(1);
        assertThat(summary.get("completedChanges").asInt()).isEqualTo(1); assertThat(summary.get("failedChanges").asInt()).isEqualTo(1);
        assertThat(summary.get("changeSuccessRate").asDouble()).isEqualTo(50.0);
        assertThat(summary.get("activeIncidents").asInt()).isEqualTo(1); assertThat(summary.get("changesByRisk").get("UNASSESSED").asInt()).isEqualTo(1);
        assertThat(summary.get("trend").get(6).get("changes").asInt()).isEqualTo(4);
    }
    @Test void dashboardUsesUtcTrendBoundariesAndLimitsRecentRows() {
        for(int i=0;i<7;i++) create(false);
        LocalDate today=LocalDate.now(ZoneOffset.UTC);
        var ids=jdbc.queryForList("SELECT id FROM change_requests ORDER BY id",Long.class);
        jdbc.update("UPDATE change_requests SET created_at=? WHERE id=?",today.minusDays(6).atStartOfDay().atOffset(ZoneOffset.UTC),ids.get(0));
        jdbc.update("UPDATE change_requests SET created_at=? WHERE id=?",today.minusDays(7).atStartOfDay().atOffset(ZoneOffset.UTC),ids.get(1));
        var summary=dashboard(admin);
        assertThat(summary.get("recentChanges").size()).isEqualTo(5);
        assertThat(summary.get("trend").get(0).get("changes").asInt()).isEqualTo(1);
        int sum=0; for(var day:summary.get("trend")) sum+=day.get("changes").asInt(); assertThat(sum).isEqualTo(6);
    }
    @Test void changeStateMachineAllowsOnlyExpectedEdges() {
        for(var from:ChangeStatus.values()) for(var to:ChangeStatus.values()) {
            boolean allowed=switch(from) { case DRAFT->to==ChangeStatus.PENDING_APPROVAL; case PENDING_APPROVAL->to==ChangeStatus.APPROVED||to==ChangeStatus.REJECTED; case REJECTED->to==ChangeStatus.DRAFT; case APPROVED->to==ChangeStatus.IMPLEMENTING; case IMPLEMENTING->to==ChangeStatus.COMPLETED||to==ChangeStatus.FAILED; default->false; };
            assertThat(from.canTransitionTo(to)).isEqualTo(allowed);
        }
    }
    private long user(String name,RoleCode role){return users.create(new CreateUserRequest(name,name+"@example.test",name,PASSWORD,Set.of(role)),adminId).id();}
    private String login(String name){return call(HttpMethod.POST,"/api/v1/auth/login",null,Map.of("username",name,"password",PASSWORD)).getBody().get("accessToken").asText();}
    private Map<String,Object> body(boolean complete){var result=new HashMap<String,Object>(Map.of("serviceId",serviceId,"title"," Connection pool change ")); if(complete) result.putAll(Map.of("businessReason","Reduce failures","riskLevel","HIGH","plannedImplementationAt",Instant.now().plusSeconds(86400).toString(),"implementationPlan","Deploy configuration","rollbackPlan","Restore previous configuration")); return result;}
    private JsonNode create(boolean complete){var result=call(HttpMethod.POST,ROOT,owner,body(complete)); assertThat(result.getStatusCode().value()).as(String.valueOf(result.getBody())).isEqualTo(201);return result.getBody();}
    private JsonNode submit(JsonNode change){return ok(call(HttpMethod.POST,path(change)+"/submit",owner,Map.of("version",version(change))));}
    private JsonNode review(JsonNode change,String decision,String comment){var payload=new HashMap<String,Object>(Map.of("version",version(change),"decision",decision));if(comment!=null)payload.put("comment",comment);return ok(call(HttpMethod.POST,path(change)+"/reviews",approver,payload));}
    private JsonNode start(JsonNode change){return ok(call(HttpMethod.POST,path(change)+"/implementation/start",owner,Map.of("version",version(change))));}
    private JsonNode finish(JsonNode change,String outcome,String result){return ok(call(HttpMethod.POST,path(change)+"/implementation/result",owner,Map.of("version",version(change),"outcome",outcome,"result",result)));}
    private JsonNode dashboard(String token){return ok(call(HttpMethod.GET,"/api/v1/dashboard/summary",token,null));}
    private String path(JsonNode change){return ROOT+"/"+change.get("id").asLong();}
    private long version(JsonNode change){return change.get("version").asLong();}
    private JsonNode ok(ResponseEntity<JsonNode> response){assertThat(response.getStatusCode().value()).as(String.valueOf(response.getBody())).isEqualTo(200);return response.getBody();}
    private void error(ResponseEntity<JsonNode> response,int status,String code){assertThat(response.getStatusCode().value()).as(String.valueOf(response.getBody())).isEqualTo(status);assertThat(response.getBody().get("code").asText()).isEqualTo(code);}
    private ResponseEntity<JsonNode> call(HttpMethod method,String path,String token,Object body){var headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);if(token!=null)headers.setBearerAuth(token);return http.exchange(path,method,new HttpEntity<>(body,headers),JsonNode.class);}
}
