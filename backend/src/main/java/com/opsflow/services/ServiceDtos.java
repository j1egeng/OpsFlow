package com.opsflow.services;

import java.time.Instant;
import java.util.List;

import com.opsflow.users.UserAccount;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;

public final class ServiceDtos {
    private ServiceDtos() {
    }

    public record SaveServiceRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,49}") String code,
            @NotBlank @Size(max = 100) @Pattern(regexp = "(?s).*[^\\p{javaWhitespace}].*") String name,
            @Size(max = 10000) String description,
            @Positive Long ownerId) {
    }

    public record UpdateServiceStatusRequest(@NotNull Boolean enabled) {
    }

    public record OwnerSummary(Long id, String username, String displayName, boolean enabled) {
        static OwnerSummary from(UserAccount owner) {
            return new OwnerSummary(owner.getId(), owner.getUsername(), owner.getDisplayName(), owner.isEnabled());
        }
    }

    public record ServiceResponse(Long id, String code, String name, String description, Long ownerId,
                                  OwnerSummary owner, boolean enabled, Long createdBy,
                                  Instant createdAt, Instant updatedAt, long version) {
        public static ServiceResponse from(SoftwareService service) {
            UserAccount owner = service.getOwner();
            return new ServiceResponse(service.getId(), service.getCode(), service.getName(), service.getDescription(),
                    owner == null ? null : owner.getId(), owner == null ? null : OwnerSummary.from(owner),
                    service.isEnabled(), service.getCreatedBy().getId(), service.getCreatedAt(), service.getUpdatedAt(),
                    service.getVersion());
        }
    }

    public record ServicePage(List<ServiceResponse> items, int page, int size, long totalElements, int totalPages) {
        static ServicePage from(Page<SoftwareService> result) {
            return new ServicePage(result.getContent().stream().map(ServiceResponse::from).toList(),
                    result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
        }
    }
}
