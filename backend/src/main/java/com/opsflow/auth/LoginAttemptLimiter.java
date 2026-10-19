package com.opsflow.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Locks a username for a while after repeated failed logins. Counts are kept in memory, so they reset
 * on restart and are not shared between backend instances; the Docker deployment runs a single backend.
 */
@Component
public class LoginAttemptLimiter {
    static final int MAX_TRACKED_USERNAMES = 100_000;

    private final int maxFailures;
    private final Duration lockout;
    private final Clock clock;
    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();

    @Autowired
    public LoginAttemptLimiter(@Value("${app.security.login.max-failures:5}") int maxFailures,
                               @Value("${app.security.login.lockout:15m}") Duration lockout) {
        this(maxFailures, lockout, Clock.systemUTC());
    }

    LoginAttemptLimiter(int maxFailures, Duration lockout, Clock clock) {
        if (maxFailures < 1 || lockout.isNegative() || lockout.isZero()) {
            throw new IllegalStateException("LOGIN_MAX_FAILURES must be at least 1 and LOGIN_LOCKOUT must be positive");
        }
        this.maxFailures = maxFailures;
        this.lockout = lockout;
        this.clock = clock;
    }

    public boolean isLocked(String username) {
        Attempts entry = attempts.get(key(username));
        return entry != null && entry.failures() >= maxFailures && clock.instant().isBefore(entry.expiresAt());
    }

    /** Failures count within one lockout-length window; reaching the limit locks for a full lockout period. */
    public void recordFailure(String username) {
        Instant now = clock.instant();
        String key = key(username);
        if (attempts.size() >= MAX_TRACKED_USERNAMES && !attempts.containsKey(key)) {
            attempts.values().removeIf(entry -> !now.isBefore(entry.expiresAt()));
            if (attempts.size() >= MAX_TRACKED_USERNAMES) return;
        }
        attempts.compute(key, (name, entry) -> {
            if (entry == null || !now.isBefore(entry.expiresAt())) return new Attempts(1, now.plus(lockout));
            int failures = entry.failures() + 1;
            return new Attempts(failures, failures >= maxFailures ? now.plus(lockout) : entry.expiresAt());
        });
    }

    public void recordSuccess(String username) {
        attempts.remove(key(username));
    }

    private static String key(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }

    private record Attempts(int failures, Instant expiresAt) { }
}
