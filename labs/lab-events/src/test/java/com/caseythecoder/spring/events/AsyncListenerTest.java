package com.caseythecoder.spring.events;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.caseythecoder.spring.support.Recorder;
import org.junit.jupiter.api.Test;

import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;

/**
 * Adding @Async to a listener moves it off the publishing thread. That buys a non-blocking
 * publisher and costs the two things people forget: the exception no longer reaches the caller, and
 * the listener no longer shares the caller's transaction or thread-local context.
 *
 * <p>The latches live in their own bean on purpose. @Async makes Spring wrap Listeners in a CGLIB
 * proxy created without calling the constructor, so reading a field off the injected reference
 * would hand you null - a good trap to have felt once.
 *
 * <p>Notes: docs/events.md, "Going async, and what you give up".
 */
@SpringJUnitConfig(AsyncListenerTest.Config.class)
class AsyncListenerTest {

    @Autowired
    ApplicationEventPublisher publisher;

    @Autowired
    Recorder recorder;

    @Autowired
    Latches latches;

    @Autowired
    Config config;

    @Test
    void publishReturnsBeforeAnAsyncListenerHasFinished() throws Exception {
        publisher.publishEvent(new Slow());
        recorder.record("publish-returned");

        assertThat(latches.started.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(recorder.labels())
                .as("the publisher did not wait%n%s", recorder)
                .doesNotContain("slow-listener-finished");

        latches.release.countDown();
        await().atMost(Duration.ofSeconds(5)).until(() -> recorder.countOf("slow-listener-finished") == 1);

        assertThat(recorder.threadsFor("slow-listener-finished"))
                .allSatisfy(thread -> assertThat(thread).startsWith("async-"));
    }

    @Test
    void anAsyncListenerFailureNeverReachesThePublisher() {
        assertThatCode(() -> publisher.publishEvent(new Boom())).doesNotThrowAnyException();

        // It is not lost, but it now lands somewhere nobody watches by default: the
        // AsyncUncaughtExceptionHandler, whose default implementation only logs.
        await().atMost(Duration.ofSeconds(5)).until(() -> config.caught.get() != null);
        assertThat(config.caught.get()).hasMessage("async listener blew up");
    }

    record Slow() {
    }

    record Boom() {
    }

    static class Latches {

        final CountDownLatch started = new CountDownLatch(1);

        final CountDownLatch release = new CountDownLatch(1);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAsync
    static class Config implements AsyncConfigurer {

        final AtomicReference<Throwable> caught = new AtomicReference<>();

        private final ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        Config() {
            executor.setCorePoolSize(2);
            executor.setThreadNamePrefix("async-");
        }

        @Bean
        Recorder recorder() {
            return new Recorder();
        }

        @Bean
        Latches latches() {
            return new Latches();
        }

        @Bean
        Listeners listeners(Recorder recorder, Latches latches) {
            return new Listeners(recorder, latches);
        }

        /** Registered as a bean so the container initialises and shuts down its threads. */
        @Bean
        ThreadPoolTaskExecutor asyncEventExecutor() {
            return executor;
        }

        @Override
        public Executor getAsyncExecutor() {
            return executor;
        }

        @Override
        public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
            return (Throwable ex, Method method, Object... params) -> caught.set(ex);
        }
    }

    static class Listeners {

        private final Recorder recorder;

        private final Latches latches;

        Listeners(Recorder recorder, Latches latches) {
            this.recorder = recorder;
            this.latches = latches;
        }

        @Async
        @EventListener
        public void onSlow(Slow event) throws InterruptedException {
            latches.started.countDown();
            latches.release.await(10, TimeUnit.SECONDS);
            recorder.record("slow-listener-finished");
        }

        @Async
        @EventListener
        public void onBoom(Boom event) {
            throw new IllegalStateException("async listener blew up");
        }
    }
}
