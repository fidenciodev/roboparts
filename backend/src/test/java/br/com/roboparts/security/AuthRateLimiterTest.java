package br.com.roboparts.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AuthRateLimiterTest {
    @Test
    void refusesAttemptsUntilTheWindowExpiresAndRoundsRetryAfterUp() {
        MutableClock clock = new MutableClock();
        AuthRateLimiter limiter = new AuthRateLimiter(clock, 2, Duration.ofSeconds(60), 10);
        assertThat(limiter.accept("client").allowed()).isTrue();
        assertThat(limiter.accept("client").allowed()).isTrue();
        assertThat(limiter.accept("client")).isEqualTo(new AuthRateLimiter.Decision(false, 60));
        clock.advance(Duration.ofMillis(59001));
        assertThat(limiter.accept("client")).isEqualTo(new AuthRateLimiter.Decision(false, 1));
        clock.advance(Duration.ofMillis(999));
        assertThat(limiter.accept("client")).isEqualTo(new AuthRateLimiter.Decision(true, 0));
    }

    @Test
    void clientFloodCannotGrowTheTrackedMapOrEvictAnExistingLimit() {
        MutableClock clock = new MutableClock();
        AuthRateLimiter limiter = new AuthRateLimiter(clock, 1, Duration.ofSeconds(10), 2);
        assertThat(limiter.accept("first").allowed()).isTrue();
        clock.advance(Duration.ofSeconds(1));
        assertThat(limiter.accept("second").allowed()).isTrue();
        for (int index = 0; index < 100; index++) {
            assertThat(limiter.accept("new-address-" + index)).isEqualTo(new AuthRateLimiter.Decision(false, 9));
        }
        assertThat(limiter.trackedClientCount()).isEqualTo(2);
        assertThat(limiter.accept("first").allowed()).isFalse();
        clock.advance(Duration.ofSeconds(9));
        assertThat(limiter.accept("third").allowed()).isTrue();
        assertThat(limiter.trackedClientCount()).isEqualTo(2);
    }

    @Test
    void simultaneousRequestsCannotExceedTheConfiguredLimit() throws Exception {
        AuthRateLimiter limiter = new AuthRateLimiter(new MutableClock(), 5, Duration.ofMinutes(1), 10);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var results = new ArrayList<Future<Boolean>>();
            for (int index = 0; index < 40; index++) {
                results.add(executor.submit(() -> {
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    return limiter.accept("same-client").allowed();
                }));
            }
            start.countDown();
            int admitted = 0;
            for (var result : results) if (result.get(10, TimeUnit.SECONDS)) admitted++;
            assertThat(admitted).isEqualTo(5);
        }
        assertThat(limiter.trackedClientCount()).isEqualTo(1);
    }

    @Test
    void rejectsInvalidLimitsBeforeServingRequests() {
        assertThatIllegalArgumentException().isThrownBy(() -> new AuthRateLimiter(0, Duration.ofSeconds(60), 10));
        assertThatIllegalArgumentException().isThrownBy(() -> new AuthRateLimiter(10001, Duration.ofSeconds(60), 10));
        assertThatIllegalArgumentException().isThrownBy(() -> new AuthRateLimiter(20, Duration.ZERO, 10));
        assertThatIllegalArgumentException().isThrownBy(() -> new AuthRateLimiter(20, Duration.ofSeconds(-1), 10));
        assertThatIllegalArgumentException().isThrownBy(() -> new AuthRateLimiter(20, Duration.ofHours(2), 10));
        assertThatIllegalArgumentException().isThrownBy(() -> new AuthRateLimiter(20, Duration.ofSeconds(60), 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new AuthRateLimiter(20, Duration.ofSeconds(60), 100001));
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-08T12:00:00Z");
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
