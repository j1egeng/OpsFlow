package com.opsflow.dashboard;

import java.time.*;
import java.util.*;
import com.opsflow.changes.ChangeStatus;
import com.opsflow.incidents.IncidentStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DashboardService {
    private final JdbcTemplate jdbc;
    public DashboardService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record Day(LocalDate date, long incidents, long changes) { }
    public record Recent(Long id, String title, String serviceCode, String status, String level, Instant createdAt) { }
    public record Summary(Instant generatedAt, String zoneId, long totalServices, long enabledServices,
            long totalIncidents, long activeIncidents, long totalChanges, long pendingApprovals, long implementingChanges,
            long completedChanges, long failedChanges, Double changeSuccessRate, Map<String,Long> incidentsByStatus,
            Map<String,Long> incidentsBySeverity, Map<String,Long> changesByStatus, Map<String,Long> changesByRisk,
            List<Day> trend, List<Recent> recentIncidents, List<Recent> recentChanges) { }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Summary summary() {
        Instant now = Instant.now();
        var incidentStatus = groups("incidents", "status", Arrays.stream(IncidentStatus.values()).map(Enum::name).toList());
        var incidentSeverity = groups("incidents", "severity", List.of("LOW", "MEDIUM", "HIGH", "CRITICAL"));
        var changeStatus = groups("change_requests", "status", Arrays.stream(ChangeStatus.values()).map(Enum::name).toList());
        var risk = groups("change_requests", "risk_level", List.of("LOW", "MEDIUM", "HIGH", "UNASSESSED"));
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        var incidentTrend = daily("incidents", today.minusDays(6), today.plusDays(1));
        var changeTrend = daily("change_requests", today.minusDays(6), today.plusDays(1));
        var trend = new ArrayList<Day>();
        for (int i = 6; i >= 0; i--) {
            LocalDate date = today.minusDays(i);
            trend.add(new Day(date, incidentTrend.getOrDefault(date, 0L), changeTrend.getOrDefault(date, 0L)));
        }
        long completed = changeStatus.get("COMPLETED"), failed = changeStatus.get("FAILED");
        Double successRate = completed + failed == 0 ? null : Math.round(completed * 10000.0 / (completed + failed)) / 100.0;
        return new Summary(now, "UTC", count("SELECT count(*) FROM software_services"), count("SELECT count(*) FROM software_services WHERE enabled=true"),
                total(incidentStatus), incidentStatus.get("OPEN") + incidentStatus.get("ASSIGNED") + incidentStatus.get("INVESTIGATING"),
                total(changeStatus), changeStatus.get("PENDING_APPROVAL"), changeStatus.get("IMPLEMENTING"), completed, failed, successRate,
                incidentStatus, incidentSeverity, changeStatus, risk, trend, recent("incidents", "severity"), recent("change_requests", "risk_level"));
    }
    private long count(String sql) { return jdbc.queryForObject(sql, Long.class); }
    private long total(Map<String,Long> counts) { return counts.values().stream().mapToLong(Long::longValue).sum(); }
    // Table and column names below are fixed internal constants, never request parameters.
    private Map<String,Long> groups(String table, String column, List<String> keys) {
        Map<String,Long> counts = new LinkedHashMap<>(); keys.forEach(key -> counts.put(key, 0L));
        jdbc.query("SELECT " + column + ", count(*) AS total FROM " + table + " GROUP BY " + column, (org.springframework.jdbc.core.RowCallbackHandler) row ->
                counts.put(row.getString(1) == null ? "UNASSESSED" : row.getString(1), row.getLong(2)));
        return counts;
    }
    private Map<LocalDate,Long> daily(String table, LocalDate from, LocalDate until) {
        Map<LocalDate,Long> counts = new HashMap<>();
        jdbc.query("SELECT CAST(created_at AT TIME ZONE 'UTC' AS date), count(*) FROM " + table
                + " WHERE created_at >= ? AND created_at < ? GROUP BY 1 ORDER BY 1", (org.springframework.jdbc.core.RowCallbackHandler) row ->
                counts.put(row.getObject(1, LocalDate.class), row.getLong(2)), from.atStartOfDay().atOffset(ZoneOffset.UTC), until.atStartOfDay().atOffset(ZoneOffset.UTC));
        return counts;
    }
    private List<Recent> recent(String table, String level) {
        return jdbc.query("SELECT r.id,r.title,s.code,r.status,r." + level + ",r.created_at FROM " + table
                + " r JOIN software_services s ON r.service_id=s.id ORDER BY r.id DESC LIMIT 5",
                (row, index) -> new Recent(row.getLong(1), row.getString(2), row.getString(3), row.getString(4), row.getString(5), row.getTimestamp(6).toInstant()));
    }
}
