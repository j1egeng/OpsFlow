package com.opsflow;

import java.util.Set;
import java.util.UUID;

import com.opsflow.auth.AuthDtos.LoginRequest;
import com.opsflow.auth.AuthService;
import com.opsflow.auth.JwtAccountConverter;
import com.opsflow.users.RoleCode;
import com.opsflow.users.UserDtos.CreateUserRequest;
import com.opsflow.users.UserRepository;
import com.opsflow.users.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "OPSFLOW_POSTGRES_TESTS", matches = "true")
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class AuthUserPostgresTests {
    private static final String PASSWORD = "PostgresTestPass123!";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordEncoder passwords;
    @Autowired private AuthService auth;
    @Autowired private UserService users;
    @Autowired private UserRepository repository;
    @Autowired private JwtDecoder decoder;
    @Autowired private JwtAccountConverter converter;

    private long actorId;
    private String actorName;

    @BeforeEach
    void createTransactionScopedAdministrator() {
        actorName = uniqueUser();
        actorId = jdbc.queryForObject("""
                INSERT INTO users(username, email, display_name, password_hash)
                VALUES (?, ?, 'PostgreSQL test administrator', ?) RETURNING id
                """, Long.class, actorName, actorName + "@example.test", passwords.encode(PASSWORD));
        jdbc.update("""
                INSERT INTO user_roles(user_id, role_id)
                SELECT ?, id FROM roles WHERE code = 'ADMINISTRATOR'
                """, actorId);
    }

    @Test
    void authenticatesAndPersistsUserRoleAndAuditAgainstRealSchema() {
        assertThat(auth.login(new LoginRequest(actorName, PASSWORD)).user().id()).isEqualTo(actorId);
        String username = uniqueUser();
        var created = users.create(new CreateUserRequest(username, username + "@example.test", "Developer",
                PASSWORD, Set.of(RoleCode.DEVELOPER)), actorId);
        assertThat(created.createdAt()).isNotNull();
        assertThat(created.updatedAt()).isNotNull();
        assertThat(created.roles()).containsExactly(RoleCode.DEVELOPER);
        assertThat(passwords.matches(PASSWORD, repository.findById(created.id()).orElseThrow().getPasswordHash())).isTrue();
        assertThat(jdbc.queryForObject("SELECT assigned_by FROM user_roles WHERE user_id = ?", Long.class, created.id()))
                .isEqualTo(actorId);
        assertThat(jdbc.queryForObject("""
                SELECT details ->> 'username' FROM audit_logs WHERE action = 'USER_CREATED' AND entity_id = ?
                """, String.class, created.id())).isEqualTo(username);
    }

    @Test
    void changesRolesAndRejectsDisabledBearerAccountAgainstRealSchema() {
        String username = uniqueUser();
        var created = users.create(new CreateUserRequest(username, username + "@example.test", "Developer",
                PASSWORD, Set.of(RoleCode.DEVELOPER)), actorId);
        var jwt = decoder.decode(auth.login(new LoginRequest(username, PASSWORD)).accessToken());
        users.replaceRoles(created.id(), Set.of(RoleCode.APPROVER), actorId);
        assertThat(converter.convert(jwt).getAuthorities()).extracting(Object::toString).containsExactly("ROLE_APPROVER");
        users.setEnabled(created.id(), false, actorId);
        assertThatThrownBy(() -> converter.convert(jwt)).isInstanceOf(InvalidBearerTokenException.class);
        assertThat(jdbc.queryForObject("SELECT enabled FROM users WHERE id = ?", Boolean.class, created.id())).isFalse();
    }

    @Test
    void bootstrapDoesNotOverwriteAnExistingAdministrator() {
        long before = repository.count();
        assertThat(users.bootstrapAdministrator(uniqueUser(), "unused@example.test", "Unused", PASSWORD)).isFalse();
        assertThat(repository.count()).isEqualTo(before);
    }

    private String uniqueUser() { return "pgtest" + UUID.randomUUID().toString().replace("-", "").substring(0, 16); }
}
