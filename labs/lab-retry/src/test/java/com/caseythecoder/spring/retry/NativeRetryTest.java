package com.caseythecoder.spring.retry;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import com.caseythecoder.spring.support.Recorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.resilience.annotation.EnableResilientMethods;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Spring Framework 7 has retry built in. {@code org.springframework.resilience} provides
 * {@code @Retryable} and {@code @ConcurrencyLimit} with no extra dependency, which makes the
 * separate {@code spring-retry} project optional for new code.
 *
 * <p>The attribute that matters most on a migration is the count. This one is named
 * {@code maxRetries}, not {@code maxAttempts}, and the tests below pin what that actually means.
 *
 * <p>Notes: docs/retry.md, "Native retry in Framework 7".
 */
@SpringJUnitConfig(NativeRetryTest.Config.class)
class NativeRetryTest {

    @Autowired
    Flaky flaky;

    @Autowired
    Counters counters;

    @Autowired
    Recorder recorder;

    @BeforeEach
    void reset() {
        recorder.clear();
        counters.calls.set(0);
        counters.failuresRemaining.set(0);
    }

    @Test
    void maxRetriesCountsRetriesNotTotalAttempts() {
        // maxRetries = 2, and the method always fails. The initial call is not a retry, so the
        // method is entered three times in total. spring-retry's maxAttempts = 2 would give two.
        counters.failuresRemaining.set(Integer.MAX_VALUE);

        assertThatThrownBy(() -> flaky.alwaysFails()).isInstanceOf(IllegalStateException.class);

        assertThat(counters.calls.get())
                .as("one initial call plus maxRetries = 2 retries")
                .isEqualTo(3);
    }

    @Test
    void aMethodThatEventuallySucceedsReturnsNormally() {
        counters.failuresRemaining.set(2);

        assertThat(flaky.alwaysFails()).isEqualTo("ok");
        assertThat(counters.calls.get()).isEqualTo(3);
    }

    @Test
    void whenRetriesRunOutTheOriginalExceptionIsWhatEscapes() {
        counters.failuresRemaining.set(Integer.MAX_VALUE);

        assertThatThrownBy(() -> flaky.alwaysFails())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("still failing");
    }

    @Test
    void onlyTheListedExceptionsAreRetried() {
        counters.failuresRemaining.set(Integer.MAX_VALUE);

        assertThatThrownBy(() -> flaky.retriesIoOnly()).isInstanceOf(IllegalArgumentException.class);

        assertThat(counters.calls.get())
                .as("IllegalArgumentException is not in includes, so it is not retried at all")
                .isEqualTo(1);
    }

    @Test
    void anIncludedExceptionIsRetried() {
        counters.failuresRemaining.set(Integer.MAX_VALUE);

        assertThatThrownBy(() -> flaky.retriesIoOnlyThrowingIo()).isInstanceOf(IOException.class);

        assertThat(counters.calls.get()).isEqualTo(3);
    }

    @Test
    void anExcludedExceptionIsNotRetriedEvenWhenItWouldOtherwiseMatch() {
        counters.failuresRemaining.set(Integer.MAX_VALUE);

        assertThatThrownBy(() -> flaky.excludesIllegalState()).isInstanceOf(IllegalStateException.class);

        assertThat(counters.calls.get()).isEqualTo(1);
    }

    @Test
    void anInternalCallIsNotRetriedAtAll() {
        // The proxy again. Retry is an interceptor like every other, so a self-call skips it.
        counters.failuresRemaining.set(Integer.MAX_VALUE);

        assertThatThrownBy(() -> flaky.callsItselfInternally()).isInstanceOf(IllegalStateException.class);

        assertThat(counters.calls.get())
                .as("no retry, because the inner call never went through the proxy")
                .isEqualTo(1);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableResilientMethods
    static class Config {

        @Bean
        Recorder recorder() {
            return new Recorder();
        }

        @Bean
        Counters counters() {
            return new Counters();
        }

        @Bean
        Flaky flaky(Recorder recorder, Counters counters) {
            return new Flaky(recorder, counters);
        }
    }

    /**
     * The counters live in their own bean because @Retryable makes Flaky a CGLIB proxy, and
     * Objenesis creates that subclass without running a constructor. Reading a field straight off
     * the injected reference gives null. This test class hit exactly that on the first run - see
     * docs/proxies.md, "What a proxy does not carry over".
     */
    static class Counters {

        final AtomicInteger calls = new AtomicInteger();

        final AtomicInteger failuresRemaining = new AtomicInteger();
    }

    static class Flaky {

        private final Recorder recorder;

        private final Counters counters;

        Flaky(Recorder recorder, Counters counters) {
            this.recorder = recorder;
            this.counters = counters;
        }

        @Retryable(maxRetries = 2, delay = 10)
        public String alwaysFails() {
            recorder.record("attempt");
            if (counters.calls.incrementAndGet() <= counters.failuresRemaining.get()) {
                throw new IllegalStateException("still failing");
            }
            return "ok";
        }

        @Retryable(includes = IOException.class, maxRetries = 2, delay = 10)
        public String retriesIoOnly() {
            counters.calls.incrementAndGet();
            throw new IllegalArgumentException("not retryable");
        }

        @Retryable(includes = IOException.class, maxRetries = 2, delay = 10)
        public String retriesIoOnlyThrowingIo() throws IOException {
            counters.calls.incrementAndGet();
            throw new IOException("retryable");
        }

        @Retryable(excludes = IllegalStateException.class, maxRetries = 2, delay = 10)
        public String excludesIllegalState() {
            counters.calls.incrementAndGet();
            throw new IllegalStateException("excluded");
        }

        public String callsItselfInternally() {
            return alwaysFails();
        }
    }
}
