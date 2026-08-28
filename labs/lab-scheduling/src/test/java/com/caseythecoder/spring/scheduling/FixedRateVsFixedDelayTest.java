package com.caseythecoder.spring.scheduling;

import java.time.Duration;
import java.util.List;

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
 * fixedRate measures from the START of the previous run; fixedDelay measures from its END. When the
 * work takes longer than the interval the two diverge permanently, which is why a job documented as
 * running every minute can quietly run every three.
 *
 * <p>Both jobs here do 150ms of work on a 100ms interval, so the expected periods are roughly 150ms
 * (rate, execution-bound) and 250ms (delay, work plus gap). The assertions are lower bounds and a
 * difference rather than exact figures, so they hold on a busy machine.
 *
 * <p>Notes: docs/scheduling.md, "fixedRate vs fixedDelay".
 */
@SpringJUnitConfig(FixedRateVsFixedDelayTest.Config.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FixedRateVsFixedDelayTest {

    private static final Duration WORK = Duration.ofMillis(150);

    @Autowired
    Recorder recorder;

    @Test
    void fixedRateChasesTheClockWhileFixedDelayAddsTheGapOnTop() {
        await().atMost(Duration.ofSeconds(15))
                .until(() -> recorder.countOf("rate") >= 5 && recorder.countOf("delay") >= 4);

        Duration ratePeriod = median(recorder.gapsFor("rate"));
        Duration delayPeriod = median(recorder.gapsFor("delay"));

        assertThat(ratePeriod)
                .as("fixedRate cannot beat the work itself; runs go back to back%n%s", recorder)
                .isGreaterThanOrEqualTo(WORK.minusMillis(20));

        assertThat(delayPeriod)
                .as("fixedDelay waits the full interval AFTER the work finishes%n%s", recorder)
                .isGreaterThanOrEqualTo(ratePeriod.plusMillis(60));
    }

    private static Duration median(List<Duration> gaps) {
        assertThat(gaps).as("need at least one gap to take a median").isNotEmpty();
        List<Duration> sorted = gaps.stream().sorted().toList();
        return sorted.get(sorted.size() / 2);
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
            // Deliberately roomy: this test is about timing rules, not about pool starvation.
            ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
            scheduler.setPoolSize(4);
            scheduler.setThreadNamePrefix("timing-");
            return scheduler;
        }
    }

    static class Jobs {

        private final Recorder recorder;

        Jobs(Recorder recorder) {
            this.recorder = recorder;
        }

        @Scheduled(fixedRate = 100)
        void atFixedRate() {
            recorder.record("rate");
            Sleeps.quietly(WORK);
        }

        @Scheduled(fixedDelay = 100)
        void atFixedDelay() {
            recorder.record("delay");
            Sleeps.quietly(WORK);
        }
    }
}
