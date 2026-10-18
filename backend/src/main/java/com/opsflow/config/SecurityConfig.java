package com.opsflow.config;

import com.opsflow.auth.JwtAccountConverter;
import com.opsflow.auth.SecurityErrorHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAccountConverter accounts,
                                           SecurityErrorHandler errors) throws Exception {
        return http.cors(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/system/info", "/actuator/health").permitAll()
                        .requestMatchers("/api/v1/users", "/api/v1/users/**").hasRole("ADMINISTRATOR")
                        .requestMatchers("/api/v1/audit-logs", "/api/v1/audit-logs/**").hasRole("ADMINISTRATOR")
                        .requestMatchers(HttpMethod.GET, "/api/v1/services", "/api/v1/services/**").authenticated()
                        .requestMatchers(HttpMethod.HEAD, "/api/v1/services", "/api/v1/services/**").authenticated()
                        .requestMatchers("/api/v1/services", "/api/v1/services/**").hasRole("ADMINISTRATOR")
                        .requestMatchers("/api/v1/incidents/assignees").hasRole("ADMINISTRATOR")
                        .requestMatchers(HttpMethod.GET, "/api/v1/incidents", "/api/v1/incidents/**").authenticated()
                        .requestMatchers(HttpMethod.HEAD, "/api/v1/incidents", "/api/v1/incidents/**").authenticated()
                        .requestMatchers("/api/v1/incidents", "/api/v1/incidents/**").hasAnyRole("ADMINISTRATOR", "DEVELOPER")
                        .requestMatchers(HttpMethod.GET, "/api/v1/changes", "/api/v1/changes/**", "/api/v1/dashboard/**").authenticated()
                        .requestMatchers(HttpMethod.HEAD, "/api/v1/changes", "/api/v1/changes/**", "/api/v1/dashboard/**").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/changes/*/reviews").hasRole("APPROVER")
                        .requestMatchers("/api/v1/changes", "/api/v1/changes/**").hasAnyRole("ADMINISTRATOR", "DEVELOPER")
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(errors).accessDeniedHandler(errors))
                .oauth2ResourceServer(resource -> resource.authenticationEntryPoint(errors).accessDeniedHandler(errors)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(accounts)))
                .build();
    }
}
