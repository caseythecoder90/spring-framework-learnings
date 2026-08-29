package com.caseythecoder.spring.startup;

import java.util.concurrent.atomic.AtomicBoolean;

import com.caseythecoder.spring.support.Recorder;
import org.junit.jupiter.api.Test;

import org.springframework.context.Lifecycle;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SmartLifecycle} is how you start something that is not a bean - a consumer, a listener, a
 * connection - at the right point in startup, and stop it at the right point in shutdown.
 *
 * <p>Two defaults in here catch people out. A plain {@link Lifecycle} bean is never started
 * automatically at all, and a {@code SmartLifecycle} that does not override {@code getPhase()}
 * starts <em>last</em>, because the default phase is {@code Integer.MAX_VALUE}.
 *
 * <p>Notes: docs/startup.md, "SmartLifecycle".
 */
class LifecyclePhasesTest {

    @Test
    void lowPhasesStartFirstAndStopLast() {
        Recorder recorder = new Recorder();
        PhasedConfig.recorder = recorder;

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(PhasedConfig.class);
            context.refresh();
        }

        assertThat(recorder.labels()).containsExactly(
                "infrastructure: start",
                "application: start",
                // Shutdown is the mirror image, so whatever your listener depends on is still
                // running when the listener stops.
                "application: stop",
                "infrastructure: stop");
    }

    @Test
    void aSmartLifecycleWithNoPhaseStartsLast() {
        Recorder recorder = new Recorder();
        DefaultPhaseConfig.recorder = recorder;

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(DefaultPhaseConfig.class);
            context.refresh();
        }

        assertThat(recorder.labels())
                .as("SmartLifecycle.DEFAULT_PHASE is Integer.MAX_VALUE, not 0")
                .containsExactly("phase zero: start", "default phase: start",
                        "default phase: stop", "phase zero: stop");
    }

    @Test
    void aPlainLifecycleBeanIsNeverStartedAutomatically() {
        Recorder recorder = new Recorder();
        PlainLifecycleConfig.recorder = recorder;

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(PlainLifecycleConfig.class);
            context.refresh();

            assertThat(recorder.labels())
                    .as("DefaultLifecycleProcessor.onRefresh only starts SmartLifecycle beans that opt in")
                    .isEmpty();

            // It is still a Lifecycle, so an explicit context.start() does reach it.
            context.start();
            assertThat(recorder.labels()).containsExactly("plain: start");
        }
    }

    @Test
    void autoStartupFalseOptsOutOfTheAutomaticStart() {
        Recorder recorder = new Recorder();
        OptOutConfig.recorder = recorder;

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(OptOutConfig.class);
            context.refresh();

            assertThat(recorder.labels()).containsExactly("eager: start");

            context.start();
            assertThat(recorder.labels()).containsExactly("eager: start", "opted out: start");
        }
    }

    @Test
    void theCallbackFormOfStopIsTheOneTheProcessorCalls() {
        // SmartLifecycle.stop(Runnable) is what DefaultLifecycleProcessor invokes; the no-arg
        // stop() is only reached through the default method. Overriding stop(Runnable) and
        // forgetting to run the callback makes shutdown block for
        // spring.lifecycle.timeout-per-shutdown-phase, which defaults to 30 seconds.
        AtomicBoolean callbackRan = new AtomicBoolean();
        Recorder recorder = new Recorder();
        CallbackConfig.recorder = recorder;
        CallbackConfig.callbackRan = callbackRan;

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(CallbackConfig.class);
            context.refresh();
        }

        assertThat(recorder.labels()).containsExactly("callback: start", "callback: stop(Runnable)");
        assertThat(callbackRan).isTrue();
    }

    // ---------------------------------------------------------------------------------------

    @Configuration(proxyBeanMethods = false)
    static class PhasedConfig {

        static Recorder recorder;

        @Bean
        SmartLifecycle infrastructure() {
            return new RecordingLifecycle(recorder, "infrastructure", -100);
        }

        @Bean
        SmartLifecycle application() {
            return new RecordingLifecycle(recorder, "application", 0);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class DefaultPhaseConfig {

        static Recorder recorder;

        @Bean
        SmartLifecycle phaseZero() {
            return new RecordingLifecycle(recorder, "phase zero", 0);
        }

        @Bean
        SmartLifecycle defaultPhase() {
            return new RecordingLifecycle(recorder, "default phase", SmartLifecycle.DEFAULT_PHASE);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class PlainLifecycleConfig {

        static Recorder recorder;

        @Bean
        Lifecycle plain() {
            return new Lifecycle() {

                private volatile boolean running;

                @Override
                public void start() {
                    recorder.record("plain: start");
                    this.running = true;
                }

                @Override
                public void stop() {
                    recorder.record("plain: stop");
                    this.running = false;
                }

                @Override
                public boolean isRunning() {
                    return this.running;
                }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class OptOutConfig {

        static Recorder recorder;

        @Bean
        SmartLifecycle eager() {
            return new RecordingLifecycle(recorder, "eager", 0, true);
        }

        @Bean
        SmartLifecycle optedOut() {
            return new RecordingLifecycle(recorder, "opted out", 0, false);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CallbackConfig {

        static Recorder recorder;

        static AtomicBoolean callbackRan;

        @Bean
        SmartLifecycle callback() {
            return new SmartLifecycle() {

                private volatile boolean running;

                @Override
                public void start() {
                    recorder.record("callback: start");
                    this.running = true;
                }

                @Override
                public void stop() {
                    recorder.record("callback: stop()");
                    this.running = false;
                }

                @Override
                public void stop(Runnable done) {
                    recorder.record("callback: stop(Runnable)");
                    this.running = false;
                    callbackRan.set(true);
                    done.run();
                }

                @Override
                public boolean isRunning() {
                    return this.running;
                }
            };
        }
    }
}
