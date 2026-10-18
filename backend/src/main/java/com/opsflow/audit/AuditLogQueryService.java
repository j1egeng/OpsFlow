package com.opsflow.audit;

import java.time.Instant;
import java.util.ArrayList;

import com.opsflow.audit.AuditLogDtos.*;
import com.opsflow.common.ApiException;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AuditLogQueryService {
    private final AuditLogRepository logs;

    public AuditLogQueryService(AuditLogRepository logs) { this.logs = logs; }

    public AuditLogPage list(int page, int size, Long actorId, String action, AuditEntityType entityType,
                             Long entityId, Instant from, Instant to) {
        if (from != null && to != null && !from.isBefore(to)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TIME_RANGE", "开始时间必须早于结束时间");
        }
        Specification<AuditLog> filters = (root, query, builder) -> {
            var predicates = new ArrayList<Predicate>();
            if (actorId != null) predicates.add(builder.equal(root.get("actor").get("id"), actorId));
            if (action != null) predicates.add(builder.equal(root.get("action"), action));
            if (entityType != null) predicates.add(builder.equal(root.get("entityType"), entityType.name()));
            if (entityId != null) predicates.add(builder.equal(root.get("entityId"), entityId));
            if (from != null) predicates.add(builder.greaterThanOrEqualTo(root.get("occurredAt"), from));
            if (to != null) predicates.add(builder.lessThan(root.get("occurredAt"), to));
            return builder.and(predicates.toArray(Predicate[]::new));
        };
        var result = logs.findAll(filters, PageRequest.of(page, size,
                Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("id"))));
        return new AuditLogPage(result.map(AuditLogSummary::from).getContent(), result.getNumber(),
                result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    public AuditLogDetail get(long id) {
        return AuditLogDetail.from(logs.findById(id).orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND, "AUDIT_LOG_NOT_FOUND", "审计日志不存在")));
    }
}
