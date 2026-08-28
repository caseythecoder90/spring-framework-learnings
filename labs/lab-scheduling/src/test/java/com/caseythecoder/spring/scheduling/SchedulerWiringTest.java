package com.caseythecoder.spring.scheduling;

import java.time.Duration;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.FixedRateTask;
import org.springframework.scheduling.config.IntervalTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.config.Task;
import org.springframework.scheduling.config.TaskManagementConfigUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code @EnableScheduling} actually puts in the context, and what {@code @Scheduled} turns
 * each method into.
 *
 * <p>Notes: docs/scheduling.md, "From annotation to running task".
 */
@SpringJUnitConfig(SchedulerWiringTest.Config.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SchedulerWiringTest {

    @Autowired
    ApplicationContext context;

    @Autowired
    ScheduledTaskHolder taskHolder;

    @Test
    void enableSchedulingRegistersOneInfrastructureBeanUnderAWellKnownName() {
        // @EnableScheduling -> @Import(SchedulingConfiguration.class) -> this bean definition.
        // Spring Boot's TaskSchedulingAutoConfiguration keys off this exact name, which is why you
        // get no TaskScheduler at all until something enables scheduling.
        //
        // Framework 7 changed the VALUE of this constant. On Boot 3.x / Framework 6.x it was
        // "org.springframework.context.annotation.internalScheduledAnnotationProcessor". Anything
        // that hard-codes the string rather than the constant breaks on the upgrade.
        assertThat(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME)
                .isEqualTo("org.springframework.scheduling.config.internalScheduledAnnotationProcessor");
        assertThat(context.containsBean(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME)).isTrue();
        assertThat(context.getBean(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME))
                .isInstanceOf(ScheduledAnnotationBeanPostProcessor.class);
    }

    @Test
    void theProcessorRunsLastSoItSeesTheProxyRatherThanTheRawBean() {
        // This ordering is the reason @Scheduled + @Transactional on the same method works: by the
        // time this BeanPostProcessor gets the bean, the auto-proxy creator has already wrapped it,
        // so the Runnable Spring builds invokes through the proxy.
        ScheduledAnnotationBeanPostProcessor processor =
                context.getBean(ScheduledAnnotationBeanPostProcessor.class);

        assertThat(processor.getOrder()).isEqualTo(Ordered.LOWEST_PRECEDENCE);
    }

    @Test
    void eachAnnotatedMethodBecomesATaskObjectWhoseTypeEncodesTheTimingRule() {
        Map<String, Task> byType = taskHolder.getScheduledTasks().stream()
                .map(ScheduledTask::getTask)
                .collect(Collectors.toMap(task -> task.getClass().getSimpleName(), Function.identity()));

        assertThat(byType).containsOnlyKeys(
                FixedDelayTask.class.getSimpleName(),
                FixedRateTask.class.getSimpleName(),
                CronTask.class.getSimpleName());

        assertThat(((IntervalTask) byType.get(FixedDelayTask.class.getSimpleName())).getIntervalDuration())
                .isEqualTo(Duration.ofSeconds(1));
        assertThat(((IntervalTask) byType.get(FixedRateTask.class.getSimpleName())).getIntervalDuration())
                .isEqualTo(Duration.ofSeconds(2));
        assertThat(((CronTask) byType.get(CronTask.class.getSimpleName())).getExpression())
                .isEqualTo("0 0 4 * * *");
    }

    @Test
    void aTaskThatHasNotRunYetReportsNoExecutionOutcome() {
        // Task.getLastExecutionOutcome() is the introspection hook added in Framework 6.2 —
        // handy for an actuator endpoint that answers "did the nightly job actually run?".
        assertThat(taskHolder.getScheduledTasks())
                .allSatisfy(scheduled -> assertThat(scheduled.getTask().getLastExecutionOutcome().status())
                        .isEqualTo(org.springframework.scheduling.config.TaskExecutionOutcome.Status.NONE));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class Config {

        @Bean
        Jobs jobs() {
            return new Jobs();
        }

        @Bean
        TaskScheduler taskScheduler() {
            ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
            scheduler.setPoolSize(1);
            scheduler.setThreadNamePrefix("wiring-");
            return scheduler;
        }
    }

    /** Initial delays are ten minutes out: this test inspects registration, it never lets a job run. */
    static class Jobs {

        @Scheduled(fixedDelay = 1000, initialDelay = 600_000)
        void withFixedDelay() {
        }

        @Scheduled(fixedRate = 2000, initialDelay = 600_000)
        void withFixedRate() {
        }

        @Scheduled(cron = "0 0 4 * * *")
        void withCron() {
        }
    }
}
