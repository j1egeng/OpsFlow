package com.opsflow.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoginAttemptLimiterTests {
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-19T00:00:00Z"));
    private final LoginAttemptLimiter limiter = new LoginAttemptLimiter(3, Duration.ofMinutes(15), clock);

    @Test
    void locksAfterTheConfiguredNumberOfFailures() {
        limiter.recordFailure("alice");
        limiter.recordFailure("alice");
        assertThat(limiter.isLocked("alice")).isFalse();
        limiter.recordFailure("alice");
        assertThat(limiter.isLocked("alice")).isTrue();
    }

    @Test
    void ignoresCaseAndSurroundingWhitespace() {
        limiter.recordFailure("Alice");
        limiter.recordFailure(" ALICE ");
        limiter.recordFailure("alice");
        assertThat(limiter.isLocked("aLiCe")).isTrue();
        assertThat(limiter.isLocked("bob")).isFalse();
    }

    @Test
    void unlocksWhenTheLockoutExpires() {
        for (int i = 0; i < 3; i++) limiter.recordFailure("alice");
        clock.advance(Duration.ofMinutes(15).minusSeconds(1));
        assertThat(limiter.isLocked("alice")).isTrue();
        clock.advance(Duration.ofSeconds(1));
        assertThat(limiter.isLocked("alice")).isFalse();
    }

    @Test
    void failuresOutsideTheWindowStartANewCount() {
        limiter.recordFailure("alice");
        limiter.recordFailure("alice");
        clock.advance(Duration.ofMinutes(15));
        limiter.recordFailure("alice");
        limiter.recordFailure("alice");
        assertThat(limiter.isLocked("alice")).isFalse();
    }

    @Test
    void successClearsEarlierFailures() {
        limiter.recordFailure("alice");
        limiter.recordFailure("alice");
        limiter.recordSuccess("alice");
        limiter.recordFailure("alice");
        limiter.recordFailure("alice");
        assertThat(limiter.isLocked("alice")).isFalse();
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThatThrownBy(() -> new LoginAttemptLimiter(0, Duration.ofMinutes(1), clock)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new LoginAttemptLimiter(1, Duration.ZERO, clock)).isInstanceOf(IllegalStateException.class);
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) { this.now = now; }

        void advance(Duration duration) { now = now.plus(duration); }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
