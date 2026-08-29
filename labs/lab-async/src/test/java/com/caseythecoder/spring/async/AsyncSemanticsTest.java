package com.caseythecoder.spring.async;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.caseythecoder.spring.support.Recorder;
import org.junit.jupiter.api.Test;

import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * What changes once a method is {@code @Async}: the caller stops waiting, the exception stops
 * arriving, and thread-locals stop travelling.
 *
 * <p>The return type is the switch for the middle one. A {@code void} method loses its exception to
 * a handler nobody has configured; a {@code CompletableFuture} keeps it.
 *
 * <p>Notes: docs/async.md, "What you give up".
 */
class AsyncSemanticsTest {

    @Test
    void theCallerReturnsBeforeTheWorkIsDone() throws Exception {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            Recorder recorder = context.getBean(Recorder.class);
            Latches latches = context.getBean(Latches.class);

            context.getBean(Jobs.class).blocking();
            recorder.record("caller returned");

            assertThat(latches.started.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(recorder.labels()).doesNotContain("blocking finished");

            latches.release.countDown();
            await().atMost(Duration.ofSeconds(5)).until(() -> recorder.countOf("blocking finished") == 1);

            assertThat(recorder.threadsFor("caller returned"))
                    .doesNotContainAnyElementsOf(recorder.threadsFor("blocking finished"));
        }
    }

    @Test
    void aVoidMethodLosesItsExceptionToTheUncaughtHandler() {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            Config config = context.getBean(Config.class);

            assertThatCode(() -> context.getBean(Jobs.class).failVoid()).doesNotThrowAnyException();

            await().atMost(Duration.ofSeconds(5)).until(() -> config.caught.get() != null);
            assertThat(config.caught.get()).hasMessage("void failure");
        }
    }

    @Test
    void aCompletableFutureCarriesTheExceptionBackToTheCaller() {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            CompletableFuture<String> future = context.getBean(Jobs.class).failFuture();

            assertThatThrownBy(future::get)
                    .isInstanceOf(ExecutionException.class)
                    .hasRootCauseMessage("future failure");
        }
    }

    @Test
    void aCompletableFutureAlsoCarriesTheResult() throws Exception {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            assertThat(context.getBean(Jobs.class).succeed().get(5, TimeUnit.SECONDS)).isEqualTo("done");
        }
    }

    @Test
    void threadLocalsDoNotTravelToTheAsyncThread() {
        // The reason a transaction, a SecurityContext or an MDC value vanishes across @Async: all
        // of them are thread-bound, and this is a different thread.
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            Jobs jobs = context.getBean(Jobs.class);
            Recorder recorder = context.getBean(Recorder.class);

            Jobs.CONTEXT.set("set-on-caller");
            try {
                jobs.readThreadLocal();
                await().atMost(Duration.ofSeconds(5)).until(() -> recorder.countOf("threadlocal:null") == 1);
            }
            finally {
                Jobs.CONTEXT.remove();
            }
        }
    }

    @Test
    void anInternalCallIsNotAsyncAtAll() {
        // Self-invocation, exactly as in docs/proxies.md. The work happens inline on the caller's
        // thread and nothing indicates that @Async was ignored.
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            Recorder recorder = context.getBean(Recorder.class);
            String callerThread = Thread.currentThread().getName();

            context.getBean(Jobs.class).callsItsOwnAsyncMethod();

            assertThat(recorder.threadsFor("inner"))
                    .as("ran on the caller's thread, synchronously")
                    .containsExactly(callerThread);
        }
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
        Jobs jobs(Recorder recorder, Latches latches) {
            return new Jobs(recorder, latches);
        }

        @Bean
        ThreadPoolTaskExecutor asyncExecutor() {
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

    static class Jobs {

        static final ThreadLocal<String> CONTEXT = new ThreadLocal<>();

        private final Recorder recorder;

        private final Latches latches;

        Jobs(Recorder recorder, Latches latches) {
            this.recorder = recorder;
            this.latches = latches;
        }

        @Async
        public void blocking() throws InterruptedException {
            latches.started.countDown();
            latches.release.await(10, TimeUnit.SECONDS);
            recorder.record("blocking finished");
        }

        @Async
        public void failVoid() {
            throw new IllegalStateException("void failure");
        }

        @Async
        public CompletableFuture<String> failFuture() {
            throw new IllegalStateException("future failure");
        }

        @Async
        public CompletableFuture<String> succeed() {
            return CompletableFuture.completedFuture("done");
        }

        @Async
        public void readThreadLocal() {
            recorder.record("threadlocal:" + CONTEXT.get());
        }

        public void callsItsOwnAsyncMethod() {
            inner();
        }

        @Async
        public void inner() {
            recorder.record("inner");
        }
    }
}
