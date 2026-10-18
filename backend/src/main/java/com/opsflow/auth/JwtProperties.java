package com.opsflow.auth;

import java.time.Duration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.security.jwt")
public record JwtProperties(@NotBlank String issuer, @NotBlank String audience, @NotNull Duration ttl,
                            String secretBase64, boolean requirePersistentKey) {
}
