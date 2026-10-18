package com.opsflow.incidents;

import java.time.Instant;
import java.util.List;
import com.opsflow.services.SoftwareService;
import com.opsflow.users.UserAccount;
import jakarta.validation.constraints.*;
import org.springframework.data.domain.Page;

public final class IncidentDtos {
    private IncidentDtos() { }
    public record CreateIncidentRequest(@NotNull @Positive Long serviceId,
            @NotBlank @Size(max = 200) @Pattern(regexp = "(?s).*[^\\p{javaWhitespace}].*") String title,
            @NotBlank @Size(max = 10000) @Pattern(regexp = "(?s).*[^\\p{javaWhitespace}].*") String description,
            @NotNull IncidentSeverity severity) { }
    public record AssignIncidentRequest(@NotNull @Positive Long assigneeId, @NotNull @PositiveOrZero Long version,
                                        @Size(max = 2000) String comment) { }
    public record TransitionRequest(@NotNull IncidentStatus status, @NotNull @PositiveOrZero Long version,
                                    @Size(max = 10000) String resolution, @Size(max = 2000) String comment) { }
    public record AddNoteRequest(@NotBlank @Size(max = 10000) @Pattern(regexp = "(?s).*[^\\p{javaWhitespace}].*") String content,
                                 @NotNull @PositiveOrZero Long version) { }
    public record Person(Long id, String username, String displayName, boolean enabled) {
        public static Person from(UserAccount user) {
            return user == null ? null : new Person(user.getId(), user.getUsername(), user.getDisplayName(), user.isEnabled());
        }
    }
    public record ServiceSummary(Long id, String code, String name, boolean enabled) {
        static ServiceSummary from(SoftwareService service) { return new ServiceSummary(service.getId(), service.getCode(), service.getName(), service.isEnabled()); }
    }
    public record Permissions(boolean canAssign, boolean canInvestigate, boolean canResolve, boolean canClose, boolean canAddNote) { }
    public record IncidentResponse(Long id, ServiceSummary service, Person reporter, Person assignee, String title,
            String description, IncidentSeverity severity, IncidentStatus status, String resolution, Long sourceChangeId,
            Instant resolvedAt, Instant closedAt, Instant createdAt, Instant updatedAt, long version, Permissions permissions) {
        static IncidentResponse from(Incident incident, Permissions permissions) {
            return new IncidentResponse(incident.getId(), ServiceSummary.from(incident.getService()), Person.from(incident.getReporter()),
                    Person.from(incident.getAssignee()), incident.getTitle(), incident.getDescription(), incident.getSeverity(), incident.getStatus(),
                    incident.getResolution(), incident.getSourceChangeId(), incident.getResolvedAt(), incident.getClosedAt(),
                    incident.getCreatedAt(), incident.getUpdatedAt(), incident.getVersion(), permissions);
        }
    }
    public record NoteResponse(Long id, Long incidentId, Person author, String content, Instant createdAt) {
        static NoteResponse from(IncidentNote note) { return new NoteResponse(note.getId(), note.getIncident().getId(), Person.from(note.getAuthor()), note.getContent(), note.getCreatedAt()); }
    }
    public record HistoryResponse(Long id, Long incidentId, IncidentStatus oldStatus, IncidentStatus newStatus,
                                   Person changedBy, String comment, Instant changedAt) {
        static HistoryResponse from(IncidentHistory history) { return new HistoryResponse(history.getId(), history.getIncident().getId(),
                history.getOldStatus(), history.getNewStatus(), Person.from(history.getChangedBy()), history.getComment(), history.getChangedAt()); }
    }
    public record PageResponse<T>(List<T> items, int page, int size, long totalElements, int totalPages) {
        static <T> PageResponse<T> from(Page<T> result) { return new PageResponse<>(result.getContent(), result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages()); }
    }
}
