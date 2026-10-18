package com.opsflow.users;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;

public final class UserDtos {
    private UserDtos() {
    }

    public record CreateUserRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{2,49}") String username,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 100) String displayName,
            @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) @NotBlank @Size(min = 12, max = 72) String password,
            @NotEmpty @Size(max = 3) Set<@NotNull RoleCode> roles) {
    }

    public record UpdateRolesRequest(@NotEmpty @Size(max = 3) Set<@NotNull RoleCode> roles) {
    }

    public record UpdateStatusRequest(@NotNull Boolean enabled) {
    }

    public record UserResponse(Long id, String username, String email, String displayName,
                               boolean enabled, List<RoleCode> roles, Instant lastLoginAt,
                               Instant createdAt, Instant updatedAt) {
        public static UserResponse from(UserAccount user) {
            return new UserResponse(user.getId(), user.getUsername(), user.getEmail(), user.getDisplayName(),
                    user.isEnabled(), user.getRoleCodes().stream().sorted().toList(), user.getLastLoginAt(),
                    user.getCreatedAt(), user.getUpdatedAt());
        }
    }

    public record UserPage(List<UserResponse> items, int page, int size, long totalElements, int totalPages) {
        public static UserPage from(Page<UserAccount> result) {
            return new UserPage(result.getContent().stream().map(UserResponse::from).toList(),
                    result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
        }
    }
}
