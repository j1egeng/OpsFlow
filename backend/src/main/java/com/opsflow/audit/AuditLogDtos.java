package com.opsflow.audit;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.opsflow.users.UserAccount;

public final class AuditLogDtos {
    private AuditLogDtos() { }

    public record ActorSummary(Long id, String username, String displayName) {
        static ActorSummary from(UserAccount actor) {
            return actor == null ? null : new ActorSummary(actor.getId(), actor.getUsername(), actor.getDisplayName());
        }
    }

    public record AuditLogSummary(Long id, ActorSummary actor, String action, String entityType,
                                  Long entityId, Instant occurredAt) {
        static AuditLogSummary from(AuditLog log) {
            return new AuditLogSummary(log.getId(), ActorSummary.from(log.getActor()), log.getAction(),
                    log.getEntityType(), log.getEntityId(), log.getOccurredAt());
        }
    }

    public record AuditLogDetail(Long id, ActorSummary actor, String action, String entityType,
                                 Long entityId, Instant occurredAt, Map<String, Object> details) {
        static AuditLogDetail from(AuditLog log) {
            return new AuditLogDetail(log.getId(), ActorSummary.from(log.getActor()), log.getAction(),
                    log.getEntityType(), log.getEntityId(), log.getOccurredAt(), log.getDetails());
        }
    }

    public record AuditLogPage(List<AuditLogSummary> items, int page, int size, long totalElements, int totalPages) { }
}
