package com.opsflow.incidents;

import java.time.Instant;
import com.opsflow.users.UserAccount;
import jakarta.persistence.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

@Entity @Table(name = "incident_status_history")
public class IncidentHistory {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "incident_id", nullable = false) private Incident incident;
    @Enumerated(EnumType.STRING) @Column(name = "old_status", length = 24) private IncidentStatus oldStatus;
    @Enumerated(EnumType.STRING) @Column(name = "new_status", nullable = false, length = 24) private IncidentStatus newStatus;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "changed_by", nullable = false) private UserAccount changedBy;
    @Column(columnDefinition = "text") private String comment;
    @Generated(event = EventType.INSERT) @ColumnDefault("CURRENT_TIMESTAMP")
    @Column(name = "changed_at", nullable = false, insertable = false, updatable = false) private Instant changedAt;
    protected IncidentHistory() { }
    IncidentHistory(Incident incident, IncidentStatus previous, UserAccount actor, String comment) {
        this.incident = incident; oldStatus = previous; newStatus = incident.getStatus(); changedBy = actor; this.comment = comment;
    }
    public Long getId() { return id; }
    public Incident getIncident() { return incident; }
    public IncidentStatus getOldStatus() { return oldStatus; }
    public IncidentStatus getNewStatus() { return newStatus; }
    public UserAccount getChangedBy() { return changedBy; }
    public String getComment() { return comment; }
    public Instant getChangedAt() { return changedAt; }
}
