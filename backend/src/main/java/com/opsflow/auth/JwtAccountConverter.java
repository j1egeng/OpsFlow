package com.opsflow.auth;

import com.opsflow.common.ApiException;
import com.opsflow.users.UserDtos.UserResponse;
import com.opsflow.users.UserService;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
public class JwtAccountConverter implements Converter<Jwt, AbstractAuthenticationToken> {
    private final UserService users;

    public JwtAccountConverter(UserService users) { this.users = users; }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        UserResponse user;
        try {
            user = users.current(Long.parseLong(jwt.getSubject()));
        } catch (NumberFormatException | ApiException error) {
            throw new InvalidBearerTokenException("Account is unavailable");
        }
        if (!user.enabled()) {
            throw new InvalidBearerTokenException("Account is unavailable");
        }
        return new JwtAuthenticationToken(jwt,
                user.roles().stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role.name())).toList(),
                user.id().toString());
    }
}
