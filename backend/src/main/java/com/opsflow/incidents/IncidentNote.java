package com.opsflow.incidents;

import java.time.Instant;
import com.opsflow.users.UserAccount;
import jakarta.persistence.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

@Entity @Table(name = "incident_notes")
public class IncidentNote {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "incident_id", nullable = false) private Incident incident;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "author_id", nullable = false) private UserAccount author;
    @Column(nullable = false, columnDefinition = "text") private String content;
    @Generated(event = EventType.INSERT) @ColumnDefault("CURRENT_TIMESTAMP")
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false) private Instant createdAt;
    protected IncidentNote() { }
    IncidentNote(Incident incident, UserAccount author, String content) { this.incident = incident; this.author = author; this.content = content; }
    public Long getId() { return id; }
    public Incident getIncident() { return incident; }
    public UserAccount getAuthor() { return author; }
    public String getContent() { return content; }
    public Instant getCreatedAt() { return createdAt; }
}
