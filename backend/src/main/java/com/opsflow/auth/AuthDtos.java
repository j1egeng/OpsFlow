package com.opsflow.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.opsflow.users.UserDtos.UserResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthDtos {
    private AuthDtos() {
    }

    public record LoginRequest(@NotBlank @Size(max = 50) String username,
                               @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
                               @NotBlank @Size(max = 72) String password) {
    }

    public record LoginResponse(String accessToken, String tokenType, long expiresIn, UserResponse user) {
    }
}
