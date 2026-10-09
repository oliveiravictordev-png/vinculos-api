package com.teste.vinculos.application;

import com.teste.vinculos.support.InMemoryLoginAttemptStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class LoginThrottleTest {

    private final InMemoryLoginAttemptStore store = new InMemoryLoginAttemptStore();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-09T12:00:00Z"));
    private final LoginThrottle throttle = new LoginThrottle(store, clock);

    @Test
    void locksAfterFiveConsecutiveFailures() {
        for (int i = 1; i < LoginThrottle.FREE_FAILURES; i++) {
            assertThat(throttle.failed("gft-admin")).isZero();
            assertThat(throttle.remainingLock("gft-admin")).isZero();
        }

        assertThat(throttle.failed("gft-admin")).isEqualTo(Duration.ofMinutes(1));
        assertThat(throttle.remainingLock("gft-admin")).isEqualTo(Duration.ofMinutes(1));
        assertThat(throttle.remainingLock("bradesco-admin")).isZero();
    }

    @Test
    void lockExpiresAndTheNextFailureDoublesIt() {
        failTimes("gft-admin", LoginThrottle.FREE_FAILURES);
        clock.advance(Duration.ofSeconds(61));

        assertThat(throttle.remainingLock("gft-admin")).isZero();
        assertThat(throttle.failed("gft-admin")).isEqualTo(Duration.ofMinutes(2));
    }

    @Test
    void lockGrowsUpToFifteenMinutes() {
        assertThat(LoginThrottle.lockFor(5)).isEqualTo(Duration.ofMinutes(1));
        assertThat(LoginThrottle.lockFor(8)).isEqualTo(Duration.ofMinutes(8));
        assertThat(LoginThrottle.lockFor(9)).isEqualTo(LoginThrottle.MAX_LOCK);
        assertThat(LoginThrottle.lockFor(500)).isEqualTo(LoginThrottle.MAX_LOCK);
    }

    @Test
    void successResetsTheCount() {
        failTimes("gft-admin", LoginThrottle.FREE_FAILURES - 1);
        throttle.succeeded("gft-admin");

        assertThat(throttle.failed("gft-admin")).isZero();
    }

    @Test
    void caseSpacesAndHugeNamesCountAsTheSameUser() {
        failTimes(" GFT-Admin ", LoginThrottle.FREE_FAILURES);

        assertThat(throttle.remainingLock("gft-admin")).isPositive();
        String huge = "x".repeat(10_000);
        failTimes(huge, LoginThrottle.FREE_FAILURES);
        assertThat(throttle.remainingLock("x".repeat(LoginThrottle.MAX_USERNAME_LENGTH))).isPositive();
        assertThat(throttle.remainingLock(null)).isZero();
    }

    private void failTimes(String username, int times) {
        for (int i = 0; i < times; i++) {
            throttle.failed(username);
        }
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
