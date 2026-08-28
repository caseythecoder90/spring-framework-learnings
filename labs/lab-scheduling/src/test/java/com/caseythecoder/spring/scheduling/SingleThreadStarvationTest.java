package com.caseythecoder.spring.scheduling;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.caseythecoder.spring.support.Recorder;
import com.caseythecoder.spring.support.Sleeps;
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
 * The bug this repo exists to make obvious: with the default one-thread scheduler, a slow job does
 * not just run slowly — it stops every other {@code @Scheduled} method in the application.
 *
 * <p>No sleeps are used to detect the stall; a latch pins the single thread deterministically.
 *
 * <p>Notes: docs/scheduling.md, "The pool of one".
 */
@SpringJUnitConfig(SingleThreadStarvationTest.Config.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SingleThreadStarvationTest {

    @Autowired
    Recorder recorder;

    @Autowired
    Jobs jobs;

    @Test
    void oneBlockedJobStopsEveryOtherScheduledMethod() throws Exception {
        assertThat(jobs.blockerStarted.await(5, TimeUnit.SECONDS))
                .as("the blocking job should have grabbed the only scheduler thread")
                .isTrue();

        // The scheduler thread is now parked inside blocker(). Nothing else can possibly run,
        // so this count is stable to read.
        long neighbourRunsWhenBlocked = recorder.countOf("neighbour");

        // Six neighbour slots (50ms apart) come and go while the thread is held.
        Sleeps.quietly(Duration.ofMillis(300));

        assertThat(recorder.countOf("neighbour"))
                .as("neighbour is scheduled every 50ms but cannot run — the pool has one thread:%n%s", recorder)
                .isEqualTo(neighbourRunsWhenBlocked);

        jobs.release.countDown();

        await().atMost(Duration.ofSeconds(5))
                .until(() -> recorder.countOf("neighbour") > neighbourRunsWhenBlocked);

        assertThat(recorder.allThreads())
                .as("both jobs ran on the same single thread")
                .hasSize(1);
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

        @Bean
        TaskScheduler taskScheduler() {
            // Exactly what Spring Boot gives you out of the box.
            ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
            scheduler.setPoolSize(1);
            scheduler.setThreadNamePrefix("starve-");
            return scheduler;
        }
    }

    static class Jobs {

        final CountDownLatch blockerStarted = new CountDownLatch(1);

        final CountDownLatch release = new CountDownLatch(1);

        private final Recorder recorder;

        Jobs(Recorder recorder) {
            this.recorder = recorder;
        }

        @Scheduled(fixedDelay = 50)
        void blocker() throws InterruptedException {
            if (blockerStarted.getCount() == 0) {
                return; // only block on the first pass
            }
            recorder.record("blocker");
            blockerStarted.countDown();
            release.await(10, TimeUnit.SECONDS);
        }

        @Scheduled(fixedDelay = 50)
        void neighbour() {
            recorder.record("neighbour");
        }
    }
}
