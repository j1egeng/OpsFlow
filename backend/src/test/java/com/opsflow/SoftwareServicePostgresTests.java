package com.opsflow;

import java.util.UUID;

import com.opsflow.common.ApiException;
import com.opsflow.services.ServiceDtos.SaveServiceRequest;
import com.opsflow.services.SoftwareServiceManager;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real PostgreSQL mapping checks. All fixture rows and audit writes roll back. */
@EnabledIfEnvironmentVariable(named = "OPSFLOW_POSTGRES_TESTS", matches = "true")
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class SoftwareServicePostgresTests {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;
    @Autowired private SoftwareServiceManager services;

    private long actorId;

    @BeforeEach
    void createTransactionScopedActor() {
        String username = unique("svcactor");
        actorId = jdbc.queryForObject("""
                INSERT INTO users(username, email, display_name, password_hash)
                VALUES (?, ?, 'Service test actor', 'not-a-login-fixture') RETURNING id
                """, Long.class, username, username + "@example.test");
    }

    @Test
    void persistsOwnerCreatorTimestampsAndJsonbAuditUsingExistingSchema() {
        String code = unique("service");
        var created = services.create(new SaveServiceRequest(code.toUpperCase(), " 服务 ", " 描述 ", actorId), actorId);
        entityManager.flush();
        assertThat(created.code()).isEqualTo(code);
        assertThat(created.createdBy()).isEqualTo(actorId);
        assertThat(created.ownerId()).isEqualTo(actorId);
        assertThat(created.createdAt()).isNotNull();
        assertThat(created.updatedAt()).isNotNull();
        assertThat(created.version()).isZero();
        entityManager.clear();
        assertThat(services.get(created.id())).isEqualTo(created);
        assertThat(jdbc.queryForObject("""
                SELECT details ->> 'code' FROM audit_logs
                WHERE entity_type='SERVICE' AND action='SERVICE_CREATED' AND entity_id=?
                """, String.class, created.id())).isEqualTo(code);
        assertThat(jdbc.queryForObject("SELECT owner_id FROM software_services WHERE id=?", Long.class, created.id()))
                .isEqualTo(actorId);
    }

    @Test
    void updatesVersionAndUsesDatabaseTimestampTriggerWhileKeepingCreationFields() {
        var created = services.create(new SaveServiceRequest(unique("update"), "Original", "Original description", actorId), actorId);
        var changed = services.update(created.id(), new SaveServiceRequest(unique("changed"), "Changed", null, null), actorId);
        assertThat(changed.version()).isEqualTo(1);
        assertThat(changed.createdAt()).isEqualTo(created.createdAt());
        assertThat(changed.createdBy()).isEqualTo(actorId);
        // The existing trigger uses clock_timestamp(), so updates advance even within one transaction.
        assertThat(changed.updatedAt()).isAfter(created.updatedAt());
        assertThat(changed.ownerId()).isNull();
        var disabled = services.setEnabled(created.id(), false, actorId);
        assertThat(disabled.version()).isEqualTo(2);
        entityManager.flush();
        entityManager.clear();
        assertThat(services.get(created.id()).enabled()).isFalse();
        assertThat(jdbc.queryForObject("""
                SELECT details -> 'previous' ->> 'name' FROM audit_logs
                WHERE action='SERVICE_UPDATED' AND entity_type='SERVICE' AND entity_id=?
                """, String.class, created.id())).isEqualTo("Original");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM audit_logs WHERE entity_type='SERVICE' AND entity_id=?
                """, Long.class, created.id())).isEqualTo(3);
    }

    @Test
    void preservesNullableFieldsAndRejectsDuplicateCodeInRealDatabase() {
        String code = unique("nullable");
        var created = services.create(new SaveServiceRequest(code, "Unassigned", "   ", null), actorId);
        entityManager.flush();
        entityManager.clear();
        assertThat(services.get(created.id()).owner()).isNull();
        assertThat(jdbc.queryForObject("""
                SELECT details ->> 'ownerId' FROM audit_logs
                WHERE action='SERVICE_CREATED' AND entity_type='SERVICE' AND entity_id=?
                """, String.class, created.id())).isNull();
        assertThatThrownBy(() -> services.create(new SaveServiceRequest(code.toUpperCase(), "Duplicate", null, null), actorId))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCode()).isEqualTo("SERVICE_CODE_EXISTS"));
    }

    private String unique(String prefix) { return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 16); }
}
