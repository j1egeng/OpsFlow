package com.opsflow.changes;
import java.time.Instant;
import com.opsflow.users.UserAccount;
import jakarta.persistence.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;
@Entity @Table(name = "change_status_history")
public class ChangeHistory {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "change_request_id", nullable = false) private ChangeRequest change;
    @Enumerated(EnumType.STRING) @Column(name = "old_status", length = 24) private ChangeStatus oldStatus;
    @Enumerated(EnumType.STRING) @Column(name = "new_status", nullable = false, length = 24) private ChangeStatus newStatus;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "changed_by", nullable = false) private UserAccount changedBy;
    @Column(columnDefinition = "text") private String comment;
    @Generated(event = EventType.INSERT) @ColumnDefault("CURRENT_TIMESTAMP") @Column(name = "changed_at", nullable = false, insertable = false, updatable = false) private Instant changedAt;
    protected ChangeHistory() { }
    ChangeHistory(ChangeRequest change, ChangeStatus previous, UserAccount actor, String comment) {
        this.change = change; oldStatus = previous; newStatus = change.getStatus(); changedBy = actor; this.comment = comment;
    }
    public Long getId() { return id; }
    public ChangeRequest getChange() { return change; }
    public ChangeStatus getOldStatus() { return oldStatus; }
    public ChangeStatus getNewStatus() { return newStatus; }
    public UserAccount getChangedBy() { return changedBy; }
    public String getComment() { return comment; }
    public Instant getChangedAt() { return changedAt; }
}
