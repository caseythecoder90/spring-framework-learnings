package com.caseythecoder.spring.scheduling;

import java.time.Duration;

import com.caseythecoder.spring.support.Recorder;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.config.TaskExecutionOutcome;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * A repeating scheduled method that throws keeps its schedule. Spring wraps it with
 * TaskUtils.LOG_AND_SUPPRESS_ERROR_HANDLER, because letting the exception reach
 * ScheduledThreadPoolExecutor would cancel the task permanently.
 *
 * <p>The cost is that a job can fail every single run and the only evidence is a log line, so the
 * second test shows the introspection API that gives you a real health signal instead.
 *
 * <p>Notes: docs/scheduling.md, "Failure is silent by design".
 */
@SpringJUnitConfig(ScheduledErrorHandlingTest.Config.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ScheduledErrorHandlingTest {

    @Autowired
    Recorder recorder;

    @Autowired
    ScheduledTaskHolder taskHolder;

    @Test
    void anExceptionEveryRunDoesNotCancelTheSchedule() {
        await().atMost(Duration.ofSeconds(10)).until(() -> recorder.countOf("attempt") >= 3);

        assertThat(recorder.countOf("attempt"))
                .as("the task is still being rescheduled despite throwing every time")
                .isGreaterThanOrEqualTo(3);
    }

    @Test
    void theFailureIsVisibleThroughTheTasksLastExecutionOutcome() {
        ScheduledTask task = taskHolder.getScheduledTasks().iterator().next();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            TaskExecutionOutcome outcome = task.getTask().getLastExecutionOutcome();
            assertThat(outcome.status()).isEqualTo(TaskExecutionOutcome.Status.ERROR);
            assertThat(outcome.throwable()).hasMessage("nightly reconciliation failed");
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class Config {

        @Bean
        Recorder recorder() {
            return new Recorder();
        }

        @Bean
        FailingJob failingJob(Recorder recorder) {
            return new FailingJob(recorder);
        }

        @Bean
        TaskScheduler taskScheduler() {
            ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
            scheduler.setPoolSize(1);
            scheduler.setThreadNamePrefix("failing-");
            return scheduler;
        }
    }

    static class FailingJob {

        private final Recorder recorder;

        FailingJob(Recorder recorder) {
            this.recorder = recorder;
        }

        @Scheduled(fixedDelay = 50)
        void alwaysThrows() {
            recorder.record("attempt");
            throw new IllegalStateException("nightly reconciliation failed");
        }
    }
}
