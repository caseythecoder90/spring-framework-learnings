package com.caseythecoder.spring.retry;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.caseythecoder.spring.support.Recorder;
import com.caseythecoder.spring.support.Sleeps;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.resilience.annotation.ConcurrencyLimit;
import org.springframework.resilience.annotation.EnableResilientMethods;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Backoff, and the other half of {@code org.springframework.resilience}: {@code @ConcurrencyLimit},
 * which bounds how many callers may be inside a method at once.
 *
 * <p>Timing assertions here are lower bounds and relative comparisons rather than exact figures, so
 * they survive a busy machine.
 *
 * <p>Notes: docs/retry.md, "Backoff" and "Concurrency limits".
 */
@SpringJUnitConfig(BackoffAndLimitTest.Config.class)
class BackoffAndLimitTest {

    @Autowired
    Flaky flaky;

    @Autowired
    Recorder recorder;

    @BeforeEach
    void reset() {
        recorder.clear();
    }

    @Test
    void aMultiplierMakesEachDelayLongerThanTheLast() {
        assertThatThrownBy(() -> flaky.backoff()).isInstanceOf(IllegalStateException.class);

        List<Duration> gaps = recorder.gapsFor("attempt");
        assertThat(gaps).as("three retries means three gaps%n%s", recorder).hasSize(3);

        assertThat(gaps.get(0)).isGreaterThanOrEqualTo(Duration.ofMillis(80));
        assertThat(gaps.get(1))
                .as("delay 100 with multiplier 2 should be about 200ms here")
                .isGreaterThan(gaps.get(0).plusMillis(40));
        assertThat(gaps.get(2))
                .as("and about 400ms here")
                .isGreaterThan(gaps.get(1).plusMillis(40));
    }

    @Test
    void maxDelayCapsTheGrowth() {
        assertThatThrownBy(() -> flaky.cappedBackoff()).isInstanceOf(IllegalStateException.class);

        List<Duration> gaps = recorder.gapsFor("capped");
        assertThat(gaps).hasSize(3);
        assertThat(gaps)
                .as("multiplier 10 would run away without maxDelay = 150%n%s", recorder)
                .allSatisfy(gap -> assertThat(gap).isLessThan(Duration.ofMillis(600)));
    }

    @Test
    void aConcurrencyLimitOfOneSerialisesCallers() throws Exception {
        int callers = 4;
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(callers);

        try {
            for (int i = 0; i < callers; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    awaitQuietly(go);
                    flaky.limited();
                });
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            pool.shutdown();
            assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
        finally {
            pool.shutdownNow();
        }

        // With a limit of 1 no two callers overlap, so entries and exits strictly alternate.
        List<String> labels = recorder.labels();
        assertThat(labels).hasSize(callers * 2);
        for (int i = 0; i < labels.size(); i += 2) {
            assertThat(labels.get(i)).isEqualTo("enter");
            assertThat(labels.get(i + 1)).isEqualTo("exit");
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableResilientMethods
    static class Config {

        @Bean
        Recorder recorder() {
            return new Recorder();
        }

        @Bean
        Flaky flaky(Recorder recorder) {
            return new Flaky(recorder);
        }
    }

    static class Flaky {

        private final Recorder recorder;

        Flaky(Recorder recorder) {
            this.recorder = recorder;
        }

        @Retryable(maxRetries = 3, delay = 100, multiplier = 2)
        public String backoff() {
            recorder.record("attempt");
            throw new IllegalStateException("always");
        }

        @Retryable(maxRetries = 3, delay = 100, multiplier = 10, maxDelay = 150)
        public String cappedBackoff() {
            recorder.record("capped");
            throw new IllegalStateException("always");
        }

        @ConcurrencyLimit(1)
        public void limited() {
            recorder.record("enter");
            Sleeps.millis(60);
            recorder.record("exit");
        }
    }
}
