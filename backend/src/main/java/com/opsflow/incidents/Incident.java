package com.opsflow.incidents;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import com.opsflow.services.SoftwareService;
import com.opsflow.users.UserAccount;
import jakarta.persistence.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

@Entity
@Table(name = "incidents")
public class Incident {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "service_id", nullable = false)
    private SoftwareService service;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "reporter_id", nullable = false)
    private UserAccount reporter;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "assignee_id")
    private UserAccount assignee;
    @Column(nullable = false, length = 200)
    private String title;
    @Column(nullable = false, columnDefinition = "text")
    private String description;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    private IncidentSeverity severity;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24)
    private IncidentStatus status = IncidentStatus.OPEN;
    @Column(columnDefinition = "text")
    private String resolution;
    @Column(name = "source_change_id", unique = true)
    private Long sourceChangeId;
    @Column(name = "resolved_at")
    private Instant resolvedAt;
    @Column(name = "closed_at")
    private Instant closedAt;
    @Generated(event = EventType.INSERT) @ColumnDefault("CURRENT_TIMESTAMP")
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;
    @Generated(event = {EventType.INSERT, EventType.UPDATE}) @ColumnDefault("CURRENT_TIMESTAMP")
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;
    @Version @Column(nullable = false)
    private long version;

    protected Incident() { }
    Incident(SoftwareService service, UserAccount reporter, String title, String description, IncidentSeverity severity) {
        this.service = service; this.reporter = reporter; this.title = title; this.description = description; this.severity = severity;
    }
    void assign(UserAccount user) {
        assignee = user;
        if (status == IncidentStatus.OPEN) status = IncidentStatus.ASSIGNED;
    }
    void linkFailedChange(long changeId) { sourceChangeId = changeId; }
    void transition(IncidentStatus target, String resolution) {
        status = target;
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        if (target == IncidentStatus.RESOLVED) {
            this.resolution = resolution;
            resolvedAt = now.isBefore(createdAt) ? createdAt : now;
        }
        if (target == IncidentStatus.CLOSED) closedAt = now.isBefore(resolvedAt) ? resolvedAt : now;
    }
    public Long getId() { return id; }
    public SoftwareService getService() { return service; }
    public UserAccount getReporter() { return reporter; }
    public UserAccount getAssignee() { return assignee; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public IncidentSeverity getSeverity() { return severity; }
    public IncidentStatus getStatus() { return status; }
    public String getResolution() { return resolution; }
    public Long getSourceChangeId() { return sourceChangeId; }
    public Instant getResolvedAt() { return resolvedAt; }
    public Instant getClosedAt() { return closedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}
