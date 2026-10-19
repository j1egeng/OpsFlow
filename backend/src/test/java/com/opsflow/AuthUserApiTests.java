package com.opsflow;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.opsflow.users.Role;
import com.opsflow.users.RoleCode;
import com.opsflow.users.RoleRepository;
import com.opsflow.users.UserAccount;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthUserApiTests {
    private static final String PASSWORD = "StrongTestPass123!";

    @Autowired private TestRestTemplate http;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserService users;
    @Autowired private PasswordEncoder passwords;
    @Autowired private JwtEncoder encoder;

    private String adminToken;
    private long adminId;

    @BeforeEach
    void prepareIsolatedInMemoryAccounts() {
        // This class only uses the H2 test profile; it never cleans a PostgreSQL database.
        H2TestData.reset(jdbc);
        for (RoleCode code : RoleCode.values()) {
            roleRepository.saveAndFlush(new Role(code, code.name()));
        }
        users.bootstrapAdministrator("admin", "admin@example.test", "Administrator", PASSWORD);
        adminId = userRepository.findByUsernameIgnoreCase("admin").orElseThrow().getId();
        adminToken = login("admin", PASSWORD).getBody().get("accessToken").asText();
    }

    @Test
    void logsInAndReturnsCurrentUserWithoutPasswordFields() {
        ResponseEntity<JsonNode> response = login("ADMIN", PASSWORD);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("tokenType").asText()).isEqualTo("Bearer");
        assertThat(response.getBody().get("expiresIn").asInt()).isEqualTo(3600);
        assertThat(response.getBody().get("user").get("lastLoginAt").isNull()).isFalse();
        ResponseEntity<JsonNode> me = request(HttpMethod.GET, "/api/v1/auth/me", adminToken, null);
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me.getBody().get("username").asText()).isEqualTo("admin");
        assertThat(me.getBody().toString()).doesNotContain("password", "passwordHash", PASSWORD);
        assertThat(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE)).isNull();
    }

    @Test
    void usesTheSameFailureForUnknownAccountAndWrongPassword() {
        ResponseEntity<JsonNode> wrong = login("admin", "WrongPassword123!");
        ResponseEntity<JsonNode> unknown = login("unknown-user", PASSWORD);
        assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(wrong.getBody().get("message")).isEqualTo(unknown.getBody().get("message"));
        assertThat(wrong.getBody().get("code").asText()).isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void locksLoginAfterRepeatedFailuresEvenWithTheCorrectPassword() {
        // A dedicated account keeps the in-memory lockout from affecting other tests in the shared context.
        String username = "lockout" + UUID.randomUUID().toString().substring(0, 8);
        assertThat(create(username, List.of("DEVELOPER")).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        for (int attempt = 0; attempt < 5; attempt++) {
            assertThat(login(username, "WrongPassword123!").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        ResponseEntity<JsonNode> locked = login(username, PASSWORD);
        assertThat(locked.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(locked.getBody().get("code").asText()).isEqualTo("TOO_MANY_LOGIN_ATTEMPTS");
        assertThat(locked.getBody().toString()).doesNotContain("accessToken");
    }

    @Test
    void rejectsUnauthenticatedProtectedRequests() {
        assertThat(request(HttpMethod.GET, "/api/v1/auth/me", null, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        ResponseEntity<JsonNode> response = request(HttpMethod.GET, "/api/v1/users", null, null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
    }

    @Test
    void administratorCreatesHashedAccountAndRecordsRoleGrantAndAudit() {
        String username = uniqueUser();
        ResponseEntity<JsonNode> response = create(username, List.of("DEVELOPER"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long id = response.getBody().get("id").asLong();
        UserAccount stored = userRepository.findById(id).orElseThrow();
        assertThat(stored.getPasswordHash()).startsWith("$2").isNotEqualTo(PASSWORD);
        assertThat(passwords.matches(PASSWORD, stored.getPasswordHash())).isTrue();
        assertThat(response.getBody().toString()).doesNotContain("password", PASSWORD);
        assertThat(jdbc.queryForObject("SELECT assigned_by FROM user_roles WHERE user_id = ?", Long.class, id)).isEqualTo(adminId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'USER_CREATED' AND entity_id = ?",
                Integer.class, id)).isEqualTo(1);
    }

    @Test
    void listsUsersWithBoundedPagination() {
        create(uniqueUser(), List.of("DEVELOPER"));
        ResponseEntity<JsonNode> response = request(HttpMethod.GET, "/api/v1/users?page=0&size=1", adminToken, null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("items").size()).isEqualTo(1);
        assertThat(response.getBody().get("totalElements").asLong()).isEqualTo(2);
        assertThat(response.getBody().get("totalPages").asInt()).isEqualTo(2);
        assertThat(response.getBody().toString()).doesNotContain("password");
    }

    @Test
    void deniesDeveloperAndApproverAccessToAllUserManagementOperations() {
        for (String role : List.of("DEVELOPER", "APPROVER")) {
            String username = uniqueUser();
            create(username, List.of(role));
            String token = login(username, PASSWORD).getBody().get("accessToken").asText();
            assertThat(request(HttpMethod.GET, "/api/v1/users", token, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(request(HttpMethod.POST, "/api/v1/users", token, userBody(uniqueUser(), List.of(role))).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(request(HttpMethod.PUT, "/api/v1/users/" + adminId + "/roles", token,
                    Map.of("roles", List.of("DEVELOPER"))).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(request(HttpMethod.PATCH, "/api/v1/users/" + adminId + "/status", token,
                    Map.of("enabled", false)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Test
    void roleChangesTakeEffectForAnAlreadyIssuedToken() {
        String username = uniqueUser();
        long id = create(username, List.of("DEVELOPER")).getBody().get("id").asLong();
        String token = login(username, PASSWORD).getBody().get("accessToken").asText();
        assertThat(request(HttpMethod.GET, "/api/v1/users", token, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(request(HttpMethod.PUT, "/api/v1/users/" + id + "/roles", adminToken,
                Map.of("roles", List.of("ADMINISTRATOR", "APPROVER"))).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(request(HttpMethod.GET, "/api/v1/users", token, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(request(HttpMethod.PUT, "/api/v1/users/" + id + "/roles", adminToken,
                Map.of("roles", List.of("DEVELOPER"))).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(request(HttpMethod.GET, "/api/v1/users", token, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void disablingAnAccountRejectsBothLoginAndExistingTokenAndAllowsReactivation() {
        String username = uniqueUser();
        long id = create(username, List.of("DEVELOPER")).getBody().get("id").asLong();
        String token = login(username, PASSWORD).getBody().get("accessToken").asText();
        assertThat(request(HttpMethod.PATCH, "/api/v1/users/" + id + "/status", adminToken,
                Map.of("enabled", false)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(login(username, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(request(HttpMethod.GET, "/api/v1/auth/me", token, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(request(HttpMethod.PATCH, "/api/v1/users/" + id + "/status", adminToken,
                Map.of("enabled", true)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(login(username, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void preservesTheLastEnabledAdministrator() {
        ResponseEntity<JsonNode> disabled = request(HttpMethod.PATCH, "/api/v1/users/" + adminId + "/status",
                adminToken, Map.of("enabled", false));
        assertThat(disabled.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(disabled.getBody().get("code").asText()).isEqualTo("LAST_ADMINISTRATOR");
        assertThat(request(HttpMethod.PUT, "/api/v1/users/" + adminId + "/roles", adminToken,
                Map.of("roles", List.of("DEVELOPER"))).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void bootstrapDoesNotResetAnExistingAdministrator() {
        String hash = userRepository.findById(adminId).orElseThrow().getPasswordHash();
        assertThat(users.bootstrapAdministrator("anotheradmin", "another@example.test", "Another", "DifferentPassword123!"))
                .isFalse();
        assertThat(userRepository.count()).isEqualTo(1);
        assertThat(userRepository.findById(adminId).orElseThrow().getPasswordHash()).isEqualTo(hash);
    }

    @Test
    void rejectsDuplicateUsernamesAndEmailsIgnoringCase() {
        String username = uniqueUser();
        assertThat(create(username, List.of("DEVELOPER")).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> duplicateUsername = new HashMap<>(userBody(username.toUpperCase(), List.of("DEVELOPER")));
        duplicateUsername.put("email", "other@example.test");
        assertThat(request(HttpMethod.POST, "/api/v1/users", adminToken, duplicateUsername).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        Map<String, Object> duplicateEmail = new HashMap<>(userBody(uniqueUser(), List.of("DEVELOPER")));
        duplicateEmail.put("email", username.toUpperCase() + "@EXAMPLE.TEST");
        assertThat(request(HttpMethod.POST, "/api/v1/users", adminToken, duplicateEmail).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void validatesPasswordsEmailAndRoleValuesWithoutEchoingSecrets() {
        for (Map.Entry<String, Object> invalid : Map.<String, Object>of(
                "password", "tiny", "email", "invalid-address", "roles", List.of("UNKNOWN"), "displayName", " ").entrySet()) {
            Map<String, Object> body = new HashMap<>(userBody(uniqueUser(), List.of("DEVELOPER")));
            body.put(invalid.getKey(), invalid.getValue());
            ResponseEntity<JsonNode> response = request(HttpMethod.POST, "/api/v1/users", adminToken, body);
            assertThat(response.getStatusCode()).as(invalid.getKey()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().toString()).doesNotContain("tiny", PASSWORD, "passwordHash");
        }
        Map<String, Object> multibyte = new HashMap<>(userBody(uniqueUser(), List.of("DEVELOPER")));
        multibyte.put("password", "密".repeat(30));
        assertThat(request(HttpMethod.POST, "/api/v1/users", adminToken, multibyte).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void validatesEmptyRolesNullStatusAndPageBounds() {
        assertThat(request(HttpMethod.PUT, "/api/v1/users/" + adminId + "/roles", adminToken,
                Map.of("roles", List.of())).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(request(HttpMethod.PATCH, "/api/v1/users/" + adminId + "/status", adminToken,
                Map.of()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        for (String query : List.of("page=-1", "size=0", "size=101", "page=wrong")) {
            assertThat(request(HttpMethod.GET, "/api/v1/users?" + query, adminToken, null).getStatusCode())
                    .as(query).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Test
    void returnsNotFoundForMissingUsers() {
        assertThat(request(HttpMethod.PUT, "/api/v1/users/9223372036854775807/roles", adminToken,
                Map.of("roles", List.of("DEVELOPER"))).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(request(HttpMethod.PATCH, "/api/v1/users/9223372036854775807/status", adminToken,
                Map.of("enabled", false)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void rejectsMalformedTamperedExpiredAndWrongIssuerOrAudienceTokens() {
        String[] parts = adminToken.split("\\.");
        parts[2] = (parts[2].startsWith("A") ? "B" : "A") + parts[2].substring(1);
        List<String> invalid = List.of("not-a-jwt", String.join(".", parts),
                token("opsflow-backend", "opsflow-api", Instant.now().minusSeconds(300), Long.toString(adminId)),
                token("wrong-issuer", "opsflow-api", Instant.now().plusSeconds(300), Long.toString(adminId)),
                token("opsflow-backend", "wrong-audience", Instant.now().plusSeconds(300), Long.toString(adminId)),
                token("opsflow-backend", "opsflow-api", Instant.now().plusSeconds(300), "not-a-user-id"));
        for (String value : invalid) {
            assertThat(request(HttpMethod.GET, "/api/v1/auth/me", value, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    private ResponseEntity<JsonNode> login(String username, String password) {
        return request(HttpMethod.POST, "/api/v1/auth/login", null, Map.of("username", username, "password", password));
    }

    private ResponseEntity<JsonNode> create(String username, List<String> roles) {
        return request(HttpMethod.POST, "/api/v1/users", adminToken, userBody(username, roles));
    }

    private Map<String, Object> userBody(String username, List<String> roles) {
        return Map.of("username", username, "email", username + "@example.test", "displayName", "Test User",
                "password", PASSWORD, "roles", roles);
    }

    private ResponseEntity<JsonNode> request(HttpMethod method, String path, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");
        if (token != null) { headers.setBearerAuth(token); }
        return http.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private String uniqueUser() { return "user" + UUID.randomUUID().toString().replace("-", "").substring(0, 12); }

    private String token(String issuer, String audience, Instant expiresAt, String subject) {
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(issuer).audience(List.of(audience)).subject(subject)
                .issuedAt(Instant.now().minusSeconds(600)).expiresAt(expiresAt).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}
