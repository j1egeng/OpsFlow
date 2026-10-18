package com.opsflow.auth;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfiguration {
    private static final Logger log = LoggerFactory.getLogger(JwtConfiguration.class);

    @Bean
    PasswordEncoder passwordEncoder(@Value("${app.security.bcrypt-strength:12}") int strength) {
        return new BCryptPasswordEncoder(strength);
    }

    @Bean
    SecretKey jwtSigningKey(JwtProperties properties) {
        if (properties.ttl().toSeconds() < 1 || properties.ttl().toSeconds() > 86400) {
            throw new IllegalStateException("JWT_TTL must be between one second and one day");
        }
        byte[] key;
        if (properties.secretBase64() == null || properties.secretBase64().isBlank()) {
            if (properties.requirePersistentKey()) {
                throw new IllegalStateException("JWT_SECRET_BASE64 is required");
            }
            key = new byte[32];
            new SecureRandom().nextBytes(key);
            log.warn("JWT_SECRET_BASE64 is unset; using an in-memory development key. Tokens become invalid after restart.");
        } else {
            try {
                key = Base64.getDecoder().decode(properties.secretBase64());
            } catch (IllegalArgumentException error) {
                throw new IllegalStateException("JWT_SECRET_BASE64 must be valid Base64");
            }
        }
        if (key.length < 32) {
            throw new IllegalStateException("JWT signing key must contain at least 32 decoded bytes");
        }
        return new SecretKeySpec(key, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSigningKey));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtSigningKey, JwtProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSigningKey).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.issuer()),
                new JwtClaimValidator<List<String>>("aud", audience -> audience != null && audience.contains(properties.audience())),
                new JwtClaimValidator<String>("sub", subject -> subject != null && !subject.isBlank()),
                new JwtClaimValidator<Instant>("exp", expiration -> expiration != null)));
        return decoder;
    }
}
