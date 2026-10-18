package com.opsflow.changes;
import java.time.Instant;
import com.opsflow.users.UserAccount;
import jakarta.persistence.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;
@Entity @Table(name = "change_reviews")
public class ChangeReview {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "change_request_id", nullable = false) private ChangeRequest change;
    @Column(name = "submission_number", nullable = false) private int submissionNumber;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "reviewer_id", nullable = false) private UserAccount reviewer;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private ReviewDecision decision;
    @Column(columnDefinition = "text") private String comment;
    @Generated(event = EventType.INSERT) @ColumnDefault("CURRENT_TIMESTAMP") @Column(name = "reviewed_at", nullable = false, insertable = false, updatable = false) private Instant reviewedAt;
    protected ChangeReview() { }
    ChangeReview(ChangeRequest change, UserAccount actor, ReviewDecision decision, String comment) {
        this.change = change; submissionNumber = change.getSubmissionNumber(); reviewer = actor; this.decision = decision; this.comment = comment;
    }
    public Long getId() { return id; }
    public ChangeRequest getChange() { return change; }
    public int getSubmissionNumber() { return submissionNumber; }
    public UserAccount getReviewer() { return reviewer; }
    public ReviewDecision getDecision() { return decision; }
    public String getComment() { return comment; }
    public Instant getReviewedAt() { return reviewedAt; }
}
