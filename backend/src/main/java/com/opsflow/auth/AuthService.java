package com.opsflow.auth;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.opsflow.audit.AuditService;
import com.opsflow.auth.AuthDtos.LoginRequest;
import com.opsflow.auth.AuthDtos.LoginResponse;
import com.opsflow.common.ApiException;
import com.opsflow.users.UserAccount;
import com.opsflow.users.UserDtos.UserResponse;
import com.opsflow.users.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final JwtEncoder encoder;
    private final JwtProperties properties;
    private final AuditService audit;
    private final LoginAttemptLimiter limiter;
    private final String dummyHash;

    public AuthService(UserRepository users, PasswordEncoder passwords, JwtEncoder encoder,
                       JwtProperties properties, AuditService audit, LoginAttemptLimiter limiter) {
        this.users = users;
        this.passwords = passwords;
        this.encoder = encoder;
        this.properties = properties;
        this.audit = audit;
        this.limiter = limiter;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public LoginResponse login(LoginRequest request) {
        if (limiter.isLocked(request.username())) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_LOGIN_ATTEMPTS", "登录失败次数过多，请稍后再试");
        }
        if (request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            limiter.recordFailure(request.username());
            throw invalidCredentials();
        }
        UserAccount user = users.findByUsernameIgnoreCase(request.username().trim()).orElse(null);
        boolean matched = passwords.matches(request.password(), user == null ? dummyHash : user.getPasswordHash());
        if (!matched || user == null || !user.isEnabled()) {
            limiter.recordFailure(request.username());
            throw invalidCredentials();
        }
        limiter.recordSuccess(request.username());
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(properties.issuer())
                .audience(List.of(properties.audience())).subject(user.getId().toString())
                .issuedAt(now).expiresAt(now.plus(properties.ttl())).id(UUID.randomUUID().toString()).build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        user.recordLogin();
        users.flush();
        audit.userEvent(user, "USER_LOGIN", user.getId(), Map.of());
        return new LoginResponse(token, "Bearer", properties.ttl().toSeconds(), UserResponse.from(user));
    }

    private ApiException invalidCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "用户名或密码错误，或账号已停用");
    }
}
