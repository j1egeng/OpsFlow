package com.opsflow.changes;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import com.opsflow.incidents.Incident;
import com.opsflow.services.SoftwareService;
import com.opsflow.users.UserAccount;
import jakarta.persistence.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;
@Entity @Table(name = "change_requests")
public class ChangeRequest {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id ;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "service_id", nullable = false)
    private SoftwareService service ;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "requester_id", nullable = false)
    private UserAccount requester ;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "related_incident_id")
    private Incident relatedIncident ;
    @Column(nullable = false, length = 200)
    private String title ;
    @Column(name = "business_reason", columnDefinition = "text")
    private String businessReason ;
    @Enumerated(EnumType.STRING) @Column(name = "risk_level", length = 16)
    private RiskLevel riskLevel ;
    @Column(name = "planned_implementation_at")
    private Instant plannedImplementationAt ;
    @Column(name = "implementation_plan", columnDefinition = "text")
    private String implementationPlan ;
    @Column(name = "rollback_plan", columnDefinition = "text")
    private String rollbackPlan ;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24)
    private ChangeStatus status = ChangeStatus.DRAFT;
    @Column(name = "submission_number", nullable = false)
    private int submissionNumber ;
    @Column(name = "submitted_at")
    private Instant submittedAt ;
    @Column(name = "implementation_started_at")
    private Instant implementationStartedAt ;
    @Column(name = "implementation_finished_at")
    private Instant implementationFinishedAt ;
    @Column(name = "implementation_result", columnDefinition = "text")
    private String implementationResult ;
    @Generated(event = EventType.INSERT) @ColumnDefault("CURRENT_TIMESTAMP")
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt ;
    @Generated(event = {EventType.INSERT, EventType.UPDATE}) @ColumnDefault("CURRENT_TIMESTAMP")
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt ;
    @Version @Column(nullable = false)
    private long version ;
    protected ChangeRequest() { }
    ChangeRequest(UserAccount requester) { this.requester = requester; }
    void replace(SoftwareService service, Incident relatedIncident, ChangeDtos.DraftRequest request) {
        this.service = service; this.relatedIncident = relatedIncident; title = request.title().strip();
        businessReason = clean(request.businessReason()); riskLevel = request.riskLevel();
        plannedImplementationAt = request.plannedImplementationAt() == null ? null : request.plannedImplementationAt().truncatedTo(ChronoUnit.MICROS);
        implementationPlan = clean(request.implementationPlan()); rollbackPlan = clean(request.rollbackPlan());
        if (status == ChangeStatus.REJECTED) { status = ChangeStatus.DRAFT; submittedAt = null; }
    }
    void submit() { status = ChangeStatus.PENDING_APPROVAL; submissionNumber++; submittedAt = after(createdAt); }
    void review(ReviewDecision decision) { status = ChangeStatus.valueOf(decision.name()); }
    void start() { status = ChangeStatus.IMPLEMENTING; implementationStartedAt = after(submittedAt); }
    void finish(ImplementationOutcome outcome, String result) {
        status = ChangeStatus.valueOf(outcome.name()); implementationResult = result.strip(); implementationFinishedAt = after(implementationStartedAt);
    }
    private Instant after(Instant minimum) { Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS); return now.isBefore(minimum) ? minimum : now; }
    private String clean(String value) { return value == null || value.isBlank() ? null : value.strip(); }
    public Long getId() { return id; }
    public SoftwareService getService() { return service; }
    public UserAccount getRequester() { return requester; }
    public Incident getRelatedIncident() { return relatedIncident; }
    public String getTitle() { return title; }
    public String getBusinessReason() { return businessReason; }
    public RiskLevel getRiskLevel() { return riskLevel; }
    public Instant getPlannedImplementationAt() { return plannedImplementationAt; }
    public String getImplementationPlan() { return implementationPlan; }
    public String getRollbackPlan() { return rollbackPlan; }
    public ChangeStatus getStatus() { return status; }
    public int getSubmissionNumber() { return submissionNumber; }
    public Instant getSubmittedAt() { return submittedAt; }
    public Instant getImplementationStartedAt() { return implementationStartedAt; }
    public Instant getImplementationFinishedAt() { return implementationFinishedAt; }
    public String getImplementationResult() { return implementationResult; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}
