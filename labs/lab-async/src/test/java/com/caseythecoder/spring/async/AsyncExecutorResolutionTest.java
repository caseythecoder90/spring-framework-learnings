package com.caseythecoder.spring.async;

import java.time.Duration;
import java.util.concurrent.Executor;

import com.caseythecoder.spring.support.Recorder;
import org.junit.jupiter.api.Test;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Which executor actually runs an {@code @Async} method, resolved per method in
 * {@code AsyncExecutionAspectSupport.determineAsyncExecutor}.
 *
 * <p>The default when nothing matches is a brand new {@code SimpleAsyncTaskExecutor}, which starts
 * a fresh platform thread for every single call and has no upper bound. It is the async equivalent
 * of scheduling's silent single-thread fallback, in the opposite direction.
 *
 * <p>Notes: docs/async.md, "Which executor runs it".
 */
class AsyncExecutorResolutionTest {

    @Test
    void withNoExecutorBeanEveryCallGetsItsOwnBrandNewThread() {
        try (var context = new AnnotationConfigApplicationContext(NoExecutorConfig.class)) {
            Recorder recorder = context.getBean(Recorder.class);
            Jobs jobs = context.getBean(Jobs.class);

            for (int i = 0; i < 4; i++) {
                jobs.work();
            }
            await().atMost(Duration.ofSeconds(5)).until(() -> recorder.countOf("work") == 4);

            assertThat(recorder.allThreads())
                    .as("SimpleAsyncTaskExecutor does not pool; it creates a thread per task, "
                            + "with no limit%n%s", recorder)
                    .hasSize(4)
                    .allSatisfy(name -> assertThat(name).contains("SimpleAsyncTaskExecutor"));
        }
    }

    @Test
    void theFallbackIsLiterallyANewSimpleAsyncTaskExecutor() {
        // AsyncExecutionInterceptor.getDefaultExecutor:
        //   return (defaultExecutor != null ? defaultExecutor : new SimpleAsyncTaskExecutor());
        try (var context = new AnnotationConfigApplicationContext(NoExecutorConfig.class)) {
            assertThat(context.getBeansOfType(Executor.class))
                    .as("nothing in the context; the interceptor made its own")
                    .isEmpty();
        }
        assertThat(new SimpleAsyncTaskExecutor()).isInstanceOf(Executor.class);
    }

    @Test
    void aBeanNamedTaskExecutorIsPickedUpAsTheDefault() {
        // The same magic name as scheduling uses, from a different constant:
        // AsyncExecutionAspectSupport.DEFAULT_TASK_EXECUTOR_BEAN_NAME.
        try (var context = new AnnotationConfigApplicationContext(NamedExecutorConfig.class)) {
            Recorder recorder = context.getBean(Recorder.class);
            context.getBean(Jobs.class).work();

            await().atMost(Duration.ofSeconds(5)).until(() -> recorder.countOf("work") == 1);
            assertThat(recorder.threadsFor("work"))
                    .allSatisfy(name -> assertThat(name).startsWith("default-"));
        }
    }

    @Test
    void aQualifierOnTheAnnotationRoutesToAnotherExecutor() {
        try (var context = new AnnotationConfigApplicationContext(NamedExecutorConfig.class)) {
            Recorder recorder = context.getBean(Recorder.class);
            context.getBean(Jobs.class).reportWork();

            await().atMost(Duration.ofSeconds(5)).until(() -> recorder.countOf("report") == 1);
            assertThat(recorder.threadsFor("report"))
                    .as("@Async(\"reportExecutor\") wins over the default")
                    .allSatisfy(name -> assertThat(name).startsWith("report-"));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAsync
    static class NoExecutorConfig {

        @Bean
        Recorder recorder() {
            return new Recorder();
        }

        @Bean
        Jobs jobs(Recorder recorder) {
            return new Jobs(recorder);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAsync
    static class NamedExecutorConfig {

        @Bean
        Recorder recorder() {
            return new Recorder();
        }

        @Bean
        Jobs jobs(Recorder recorder) {
            return new Jobs(recorder);
        }

        @Bean
        ThreadPoolTaskExecutor taskExecutor() {
            return named("default-");
        }

        @Bean
        ThreadPoolTaskExecutor reportExecutor() {
            return named("report-");
        }

        private static ThreadPoolTaskExecutor named(String prefix) {
            ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
            executor.setCorePoolSize(2);
            executor.setThreadNamePrefix(prefix);
            return executor;
        }
    }

    static class Jobs {

        private final Recorder recorder;

        Jobs(Recorder recorder) {
            this.recorder = recorder;
        }

        @Async
        public void work() {
            recorder.record("work");
        }

        @Async("reportExecutor")
        public void reportWork() {
            recorder.record("report");
        }
    }
}
