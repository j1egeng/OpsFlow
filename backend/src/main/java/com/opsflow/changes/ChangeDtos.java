package com.opsflow.changes;

import java.time.Instant;
import java.util.List;
import com.opsflow.incidents.IncidentDtos.Person;
import jakarta.validation.constraints.*;
import org.springframework.data.domain.Page;

public final class ChangeDtos {
    private ChangeDtos() { }
    public record DraftRequest(@NotNull @Positive Long serviceId, @Positive Long relatedIncidentId,
            @NotBlank @Size(max = 200) @Pattern(regexp = "(?s).*[^\\p{javaWhitespace}].*") String title,
            @Size(max = 10000) String businessReason, RiskLevel riskLevel, Instant plannedImplementationAt,
            @Size(max = 10000) String implementationPlan, @Size(max = 10000) String rollbackPlan,
            @PositiveOrZero Long version) { }
    public record VersionRequest(@NotNull @PositiveOrZero Long version) { }
    public record ReviewRequest(@NotNull @PositiveOrZero Long version, @NotNull ReviewDecision decision, @Size(max = 2000) String comment) { }
    public record ResultRequest(@NotNull @PositiveOrZero Long version, @NotNull ImplementationOutcome outcome,
            @NotBlank @Size(max = 10000) @Pattern(regexp = "(?s).*[^\\p{javaWhitespace}].*") String result) { }
    public record ServiceSummary(Long id, String code, String name, boolean enabled) { }
    public record Permissions(boolean canEdit, boolean canSubmit, boolean canReview, boolean canStart, boolean canFinish) { }
    public record ChangeResponse(Long id, ServiceSummary service, Person requester, Long relatedIncidentId, String title,
            String businessReason, RiskLevel riskLevel, Instant plannedImplementationAt, String implementationPlan, String rollbackPlan,
            ChangeStatus status, int submissionNumber, Instant submittedAt, Instant implementationStartedAt, Instant implementationFinishedAt,
            String implementationResult, Long failureIncidentId, Instant createdAt, Instant updatedAt, long version, Permissions permissions) {
        static ChangeResponse from(ChangeRequest change, Long failureIncidentId, Permissions permissions) {
            var service = change.getService();
            return new ChangeResponse(change.getId(), new ServiceSummary(service.getId(), service.getCode(), service.getName(), service.isEnabled()),
                    Person.from(change.getRequester()), change.getRelatedIncident() == null ? null : change.getRelatedIncident().getId(),
                    change.getTitle(), change.getBusinessReason(), change.getRiskLevel(), change.getPlannedImplementationAt(), change.getImplementationPlan(),
                    change.getRollbackPlan(), change.getStatus(), change.getSubmissionNumber(), change.getSubmittedAt(), change.getImplementationStartedAt(),
                    change.getImplementationFinishedAt(), change.getImplementationResult(), failureIncidentId, change.getCreatedAt(), change.getUpdatedAt(), change.getVersion(), permissions);
        }
    }
    public record ReviewResponse(Long id, int submissionNumber, Person reviewer, ReviewDecision decision, String comment, Instant reviewedAt) {
        static ReviewResponse from(ChangeReview review) { return new ReviewResponse(review.getId(), review.getSubmissionNumber(), Person.from(review.getReviewer()), review.getDecision(), review.getComment(), review.getReviewedAt()); }
    }
    public record HistoryResponse(Long id, ChangeStatus oldStatus, ChangeStatus newStatus, Person changedBy, String comment, Instant changedAt) {
        static HistoryResponse from(ChangeHistory history) { return new HistoryResponse(history.getId(), history.getOldStatus(), history.getNewStatus(), Person.from(history.getChangedBy()), history.getComment(), history.getChangedAt()); }
    }
    public record PageResponse<T>(List<T> items, int page, int size, long totalElements, int totalPages) {
        public static <T> PageResponse<T> from(Page<T> page) { return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages()); }
    }
}
