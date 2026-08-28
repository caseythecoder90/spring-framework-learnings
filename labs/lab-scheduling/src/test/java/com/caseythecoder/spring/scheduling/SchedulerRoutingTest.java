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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Two schedulers in one context. The unqualified job lands on the bean literally named
 * taskScheduler; the qualified one is routed by TaskSchedulerRouter to the bean named in the
 * annotation.
 *
 * <p>This is the fix for pool starvation that does not require sizing one pool for every job at
 * once: give the slow job its own pool.
 *
 * <p>Notes: docs/scheduling.md, "Which scheduler actually runs it".
 */
@SpringJUnitConfig(SchedulerRoutingTest.Config.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SchedulerRoutingTest {

    @Autowired
    Recorder recorder;

    @Test
    void anUnqualifiedJobFallsBackToTheBeanNamedTaskScheduler() {
        await().atMost(Duration.ofSeconds(5)).until(() -> recorder.countOf("plain") >= 1);

        assertThat(recorder.threadsFor("plain"))
                .allSatisfy(thread -> assertThat(thread).startsWith("default-"));
    }

    @Test
    void aQualifiedJobIsRoutedToItsOwnScheduler() {
        await().atMost(Duration.ofSeconds(5)).until(() -> recorder.countOf("report") >= 1);

        assertThat(recorder.threadsFor("report"))
                .allSatisfy(thread -> assertThat(thread).startsWith("report-"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class Config {

        @Bean
        Recorder recorder() {
            return new Recorder();
        }

        @Bean
        Jobs jobs(Recorder recorder) {
            return new Jobs(recorder);
        }

        /** The magic name: TaskSchedulerRouter.DEFAULT_TASK_SCHEDULER_BEAN_NAME. */
        @Bean
        TaskScheduler taskScheduler() {
            ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
            scheduler.setPoolSize(1);
            scheduler.setThreadNamePrefix("default-");
            return scheduler;
        }

        @Bean
        TaskScheduler reportScheduler() {
            ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
            scheduler.setPoolSize(2);
            scheduler.setThreadNamePrefix("report-");
            return scheduler;
        }
    }

    static class Jobs {

        private final Recorder recorder;

        Jobs(Recorder recorder) {
            this.recorder = recorder;
        }

        @Scheduled(fixedDelay = 100)
        void plain() {
            recorder.record("plain");
        }

        @Scheduled(fixedDelay = 100, scheduler = "reportScheduler")
        void report() {
            recorder.record("report");
        }
    }
}
