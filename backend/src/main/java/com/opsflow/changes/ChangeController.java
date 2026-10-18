package com.opsflow.changes;
import com.opsflow.changes.ChangeDtos.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
@Validated @RestController @RequestMapping("/api/v1/changes")
public class ChangeController {
    private final ChangeService changes;
    public ChangeController(ChangeService changes) { this.changes = changes; }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public ChangeResponse create(@Valid @RequestBody DraftRequest request, @AuthenticationPrincipal Jwt jwt) { return changes.create(request, actor(jwt)); }
    @GetMapping
    public PageResponse<ChangeResponse> list(@RequestParam(defaultValue="0") @Min(0) int page, @RequestParam(defaultValue="20") @Min(1) @Max(100) int size,
            @RequestParam(required=false) ChangeStatus status, @RequestParam(required=false) RiskLevel riskLevel,
            @RequestParam(required=false) @Positive Long serviceId, @RequestParam(required=false) @Positive Long requesterId, @AuthenticationPrincipal Jwt jwt) {
        return changes.list(page, size, status, riskLevel, serviceId, requesterId, actor(jwt));
    }
    @GetMapping("/{id}") public ChangeResponse get(@PathVariable @Positive long id, @AuthenticationPrincipal Jwt jwt) { return changes.get(id, actor(jwt)); }
    @PutMapping("/{id}") public ChangeResponse update(@PathVariable @Positive long id, @Valid @RequestBody DraftRequest request, @AuthenticationPrincipal Jwt jwt) { return changes.update(id, request, actor(jwt)); }
    @PostMapping("/{id}/submit") public ChangeResponse submit(@PathVariable @Positive long id, @Valid @RequestBody VersionRequest request, @AuthenticationPrincipal Jwt jwt) { return changes.submit(id, request.version(), actor(jwt)); }
    @PostMapping("/{id}/reviews") public ChangeResponse review(@PathVariable @Positive long id, @Valid @RequestBody ReviewRequest request, @AuthenticationPrincipal Jwt jwt) { return changes.review(id, request, actor(jwt)); }
    @PostMapping("/{id}/implementation/start") public ChangeResponse start(@PathVariable @Positive long id, @Valid @RequestBody VersionRequest request, @AuthenticationPrincipal Jwt jwt) { return changes.start(id, request.version(), actor(jwt)); }
    @PostMapping("/{id}/implementation/result") public ChangeResponse finish(@PathVariable @Positive long id, @Valid @RequestBody ResultRequest request, @AuthenticationPrincipal Jwt jwt) { return changes.finish(id, request, actor(jwt)); }
    @GetMapping("/{id}/reviews") public PageResponse<ReviewResponse> reviews(@PathVariable @Positive long id, @RequestParam(defaultValue="0") @Min(0) int page, @RequestParam(defaultValue="20") @Min(1) @Max(100) int size) { return changes.reviews(id, page, size); }
    @GetMapping("/{id}/history") public PageResponse<HistoryResponse> history(@PathVariable @Positive long id, @RequestParam(defaultValue="0") @Min(0) int page, @RequestParam(defaultValue="20") @Min(1) @Max(100) int size) { return changes.history(id, page, size); }
    private long actor(Jwt jwt) { return Long.parseLong(jwt.getSubject()); }
}
