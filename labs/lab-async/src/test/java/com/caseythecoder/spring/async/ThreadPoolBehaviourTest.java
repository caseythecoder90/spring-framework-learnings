package com.caseythecoder.spring.async;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.caseythecoder.spring.support.Recorder;
import com.caseythecoder.spring.support.Sleeps;
import org.junit.jupiter.api.Test;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The most expensive misunderstanding about thread pools, and it is not Spring's fault:
 * {@code ThreadPoolExecutor} only grows past its core size when the <strong>queue is full</strong>.
 *
 * <p>With the default unbounded queue, the queue is never full, so {@code maxPoolSize} is dead
 * configuration. A pool advertised as "core 8, max 200" is a pool of 8 with an unbounded backlog.
 *
 * <p>Notes: docs/async.md, "The queue fills before the pool grows".
 */
class ThreadPoolBehaviourTest {

    @Test
    void anUnboundedQueueMeansTheMaxPoolSizeIsNeverReached() throws Exception {
        Recorder recorder = new Recorder();
        CountDownLatch hold = new CountDownLatch(1);
        CountDownLatch firstStarted = new CountDownLatch(1);

        ThreadPoolTaskExecutor executor = pool(1, 10, Integer.MAX_VALUE, "unbounded-");
        try {
            for (int i = 0; i < 5; i++) {
                executor.execute(() -> {
                    recorder.record("start");
                    firstStarted.countDown();
                    awaitQuietly(hold);
                });
            }

            assertThat(firstStarted.await(5, TimeUnit.SECONDS)).isTrue();
            Sleeps.quietly(Duration.ofMillis(250));

            assertThat(recorder.allThreads())
                    .as("core is 1, max is 10, and the queue never fills, so the pool never grows%n%s", recorder)
                    .hasSize(1);
            assertThat(recorder.countOf("start")).isEqualTo(1);
        }
        finally {
            hold.countDown();
            executor.shutdown();
        }
    }

    @Test
    void aBoundedQueueIsWhatMakesMaxPoolSizeMeanSomething() throws Exception {
        Recorder recorder = new Recorder();
        CountDownLatch hold = new CountDownLatch(1);

        // core 1, queue 1, max 10. Task 1 runs, task 2 queues, tasks 3-5 each force a new thread.
        ThreadPoolTaskExecutor executor = pool(1, 10, 1, "bounded-");
        try {
            for (int i = 0; i < 5; i++) {
                executor.execute(() -> {
                    recorder.record("start");
                    awaitQuietly(hold);
                });
            }

            await().atMost(Duration.ofSeconds(5)).until(() -> recorder.countOf("start") == 4);

            assertThat(recorder.allThreads())
                    .as("one core thread plus three grown ones; the fifth task sits in the queue%n%s", recorder)
                    .hasSize(4);
        }
        finally {
            hold.countDown();
            executor.shutdown();
        }
    }

    @Test
    void bootsDefaultExecutorHasEightCoreThreadsAndAnUnboundedQueue() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TaskExecutionAutoConfiguration.class))
                .run(context -> {
                    ThreadPoolTaskExecutor executor = context.getBean(ThreadPoolTaskExecutor.class);

                    assertThat(executor.getCorePoolSize()).isEqualTo(8);
                    assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity())
                            .as("spring.task.execution.pool.queue-capacity defaults to Integer.MAX_VALUE, "
                                    + "which is why raising max-size alone changes nothing")
                            .isEqualTo(Integer.MAX_VALUE);
                });
    }

    @Test
    void raisingMaxSizeAloneDoesNothingUntilTheQueueIsBounded() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TaskExecutionAutoConfiguration.class))
                .withPropertyValues(
                        "spring.task.execution.pool.max-size=64",
                        "spring.task.execution.pool.queue-capacity=25")
                .run(context -> {
                    ThreadPoolTaskExecutor executor = context.getBean(ThreadPoolTaskExecutor.class);

                    assertThat(executor.getMaxPoolSize()).isEqualTo(64);
                    assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity())
                            .as("now the queue can fill, so the pool can actually grow to max")
                            .isEqualTo(25);
                });
    }

    private static ThreadPoolTaskExecutor pool(int core, int max, int queue, String prefix) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(max);
        executor.setQueueCapacity(queue);
        executor.setThreadNamePrefix(prefix);
        executor.initialize();
        return executor;
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
