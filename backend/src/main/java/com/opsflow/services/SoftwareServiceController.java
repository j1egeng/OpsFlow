package com.opsflow.services;

import com.opsflow.services.ServiceDtos.SaveServiceRequest;
import com.opsflow.services.ServiceDtos.ServicePage;
import com.opsflow.services.ServiceDtos.ServiceResponse;
import com.opsflow.services.ServiceDtos.UpdateServiceStatusRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/services")
public class SoftwareServiceController {
    private final SoftwareServiceManager services;

    public SoftwareServiceController(SoftwareServiceManager services) { this.services = services; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ServiceResponse create(@Valid @RequestBody SaveServiceRequest request, @AuthenticationPrincipal Jwt jwt) {
        return services.create(request, Long.parseLong(jwt.getSubject()));
    }

    @GetMapping
    public ServicePage list(@RequestParam(defaultValue = "0") @Min(0) int page,
                            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
                            @RequestParam(required = false) Boolean enabled) {
        return services.list(page, size, enabled);
    }

    @GetMapping("/{id}")
    public ServiceResponse get(@PathVariable @Positive long id) { return services.get(id); }

    @PutMapping("/{id}")
    public ServiceResponse update(@PathVariable @Positive long id, @Valid @RequestBody SaveServiceRequest request,
                                  @AuthenticationPrincipal Jwt jwt) {
        return services.update(id, request, Long.parseLong(jwt.getSubject()));
    }

    @PatchMapping("/{id}/status")
    public ServiceResponse status(@PathVariable @Positive long id,
                                  @Valid @RequestBody UpdateServiceStatusRequest request,
                                  @AuthenticationPrincipal Jwt jwt) {
        return services.setEnabled(id, request.enabled(), Long.parseLong(jwt.getSubject()));
    }
}
