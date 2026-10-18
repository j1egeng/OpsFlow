package com.opsflow.audit;

import java.time.Instant;

import com.opsflow.audit.AuditLogDtos.*;
import jakarta.validation.constraints.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Validated
@RestController
@RequestMapping("/api/v1/audit-logs")
public class AuditLogController {
    private final AuditLogQueryService logs;

    public AuditLogController(AuditLogQueryService logs) { this.logs = logs; }

    @GetMapping
    public AuditLogPage list(@RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) @Positive Long actorId,
            @RequestParam(required = false) @Size(min = 1, max = 80)
                @Pattern(regexp = "(?s).*[^\\p{javaWhitespace}].*") String action,
            @RequestParam(required = false) AuditEntityType entityType,
            @RequestParam(required = false) @Positive Long entityId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return logs.list(page, size, actorId, action, entityType, entityId, from, to);
    }

    @GetMapping("/{id}")
    public AuditLogDetail get(@PathVariable @Positive long id) { return logs.get(id); }
}
