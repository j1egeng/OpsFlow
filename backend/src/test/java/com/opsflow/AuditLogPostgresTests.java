package com.opsflow;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.opsflow.audit.AuditEntityType;
import com.opsflow.audit.AuditLogQueryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/** Uses transaction-scoped rows only; no fixture reset and no committed data changes. */
@EnabledIfEnvironmentVariable(named = "OPSFLOW_POSTGRES_TESTS", matches = "true")
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class AuditLogPostgresTests {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AuditLogQueryService logs;

    @Test
    void readsJsonbNestedNullValuesAndActorSummaryFromRealSchema() {
        String username = "audit" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        long actor = jdbc.queryForObject("""
                INSERT INTO users(username,email,display_name,password_hash)
                VALUES (?,?,'Audit PG actor','not-a-login-fixture') RETURNING id
                """, Long.class, username, username + "@example.test");
        String action = "PG_JSON_" + UUID.randomUUID().toString().replace("-", "");
        long id = jdbc.queryForObject("""
                INSERT INTO audit_logs(actor_id,action,entity_type,entity_id,details)
                VALUES (?,?,'USER',?, '{"previous":{"enabled":true},"ownerId":null}'::jsonb) RETURNING id
                """, Long.class, actor, action, actor);
        var detail = logs.get(id);
        assertThat(detail.actor().id()).isEqualTo(actor);
        assertThat(detail.actor().username()).isEqualTo(username);
        assertThat(detail.details()).containsEntry("ownerId", null).containsKey("previous");
        var page = logs.list(0, 20, actor, action, AuditEntityType.USER, actor, null, null);
        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.items().getFirst().id()).isEqualTo(id);
    }

    @Test
    void filtersTimestamptzOrdersTiesAndPreservesNullActor() {
        String action = "PG_TIME_" + UUID.randomUUID().toString().replace("-", "");
        long first = event(action, "2026-10-01T08:00:00+08:00");
        long tied = event(action, "2026-10-01T00:00:00Z");
        event(action, "2026-10-02T00:00:00Z");
        var page = logs.list(0, 20, null, action, AuditEntityType.SERVICE, 1L,
                Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-02T00:00:00Z"));
        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.items()).extracting(item -> item.id()).containsExactly(tied, first);
        assertThat(logs.get(first).actor()).isNull();
        assertThat(logs.get(first).details()).isEmpty();
    }

    private long event(String action, String time) {
        return jdbc.queryForObject("""
                INSERT INTO audit_logs(action,entity_type,entity_id,occurred_at)
                VALUES (?,'SERVICE',1,?) RETURNING id
                """, Long.class, action, OffsetDateTime.parse(time));
    }
}
