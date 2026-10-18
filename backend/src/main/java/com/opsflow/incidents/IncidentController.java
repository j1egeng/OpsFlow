package com.opsflow.incidents;

import com.opsflow.incidents.IncidentDtos.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Validated @RestController @RequestMapping("/api/v1/incidents")
public class IncidentController {
    private final IncidentService incidents;
    public IncidentController(IncidentService incidents) { this.incidents = incidents; }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public IncidentResponse create(@Valid @RequestBody CreateIncidentRequest request, @AuthenticationPrincipal Jwt jwt) { return incidents.create(request, actor(jwt)); }
    @GetMapping
    public PageResponse<IncidentResponse> list(@RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) IncidentStatus status, @RequestParam(required = false) IncidentSeverity severity,
            @RequestParam(required = false) @Positive Long serviceId, @RequestParam(required = false) @Positive Long assigneeId,
            @AuthenticationPrincipal Jwt jwt) { return incidents.list(page, size, status, severity, serviceId, assigneeId, actor(jwt)); }
    @GetMapping("/{id}")
    public IncidentResponse get(@PathVariable @Positive long id, @AuthenticationPrincipal Jwt jwt) { return incidents.get(id, actor(jwt)); }
    @GetMapping("/assignees")
    public PageResponse<Person> assignees(@RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size, @AuthenticationPrincipal Jwt jwt) { return incidents.assignees(page, size, actor(jwt)); }
    @PostMapping("/{id}/assignment")
    public IncidentResponse assign(@PathVariable @Positive long id, @Valid @RequestBody AssignIncidentRequest request,
            @AuthenticationPrincipal Jwt jwt) { return incidents.assign(id, request, actor(jwt)); }
    @PostMapping("/{id}/transitions")
    public IncidentResponse transition(@PathVariable @Positive long id, @Valid @RequestBody TransitionRequest request,
            @AuthenticationPrincipal Jwt jwt) { return incidents.transition(id, request, actor(jwt)); }
    @PostMapping("/{id}/notes") @ResponseStatus(HttpStatus.CREATED)
    public NoteResponse addNote(@PathVariable @Positive long id, @Valid @RequestBody AddNoteRequest request,
            @AuthenticationPrincipal Jwt jwt) { return incidents.addNote(id, request, actor(jwt)); }
    @GetMapping("/{id}/notes")
    public PageResponse<NoteResponse> notes(@PathVariable @Positive long id, @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) { return incidents.notes(id, page, size); }
    @GetMapping("/{id}/history")
    public PageResponse<HistoryResponse> history(@PathVariable @Positive long id, @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) { return incidents.history(id, page, size); }
    private long actor(Jwt jwt) { return Long.parseLong(jwt.getSubject()); }
}
