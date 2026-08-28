package com.caseythecoder.spring.scheduling;

import org.junit.jupiter.api.Test;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring Boot's opinion about scheduling, pinned down. This is where the production surprise
 * lives: the default pool is <strong>one thread</strong>.
 *
 * <p>Notes: docs/scheduling.md, "The pool of one".
 */
class BootSchedulerDefaultsTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class));

    @Test
    void bootCreatesNoSchedulerAtAllUntilSomethingEnablesScheduling() {
        // TaskSchedulingConfigurations.TaskSchedulerConfiguration is
        // @ConditionalOnBean(name = "...internalScheduledAnnotationProcessor").
        runner.run(context -> assertThat(context).doesNotHaveBean(TaskScheduler.class));
    }

    @Test
    void theDefaultSchedulerIsAOneThreadPool() {
        runner.withUserConfiguration(SchedulingEnabled.class).run(context -> {
            assertThat(context).hasSingleBean(TaskScheduler.class);

            ThreadPoolTaskScheduler scheduler = context.getBean(ThreadPoolTaskScheduler.class);
            assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize())
                    .as("spring.task.scheduling.pool.size defaults to 1 — every @Scheduled method "
                            + "in the application shares this single thread")
                    .isEqualTo(1);
            assertThat(scheduler.getThreadNamePrefix()).isEqualTo("scheduling-");
        });
    }

    @Test
    void thePoolSizeIsAOneLineFix() {
        runner.withUserConfiguration(SchedulingEnabled.class)
                .withPropertyValues("spring.task.scheduling.pool.size=8")
                .run(context -> assertThat(context.getBean(ThreadPoolTaskScheduler.class)
                        .getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(8));
    }

    @Test
    void yourOwnTaskSchedulerBeanSuppressesBootsEntirely() {
        runner.withUserConfiguration(SchedulingEnabled.class, CustomScheduler.class).run(context -> {
            assertThat(context).hasSingleBean(TaskScheduler.class);
            assertThat(context.getBean(ThreadPoolTaskScheduler.class).getThreadNamePrefix())
                    .isEqualTo("mine-");
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class SchedulingEnabled {
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomScheduler {

        @org.springframework.context.annotation.Bean
        TaskScheduler taskScheduler() {
            ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
            scheduler.setPoolSize(4);
            scheduler.setThreadNamePrefix("mine-");
            return scheduler;
        }
    }
}
