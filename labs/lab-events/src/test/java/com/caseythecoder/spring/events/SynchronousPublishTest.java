package com.caseythecoder.spring.events;

import com.caseythecoder.spring.support.Recorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.context.event.SimpleApplicationEventMulticaster;
import org.springframework.core.annotation.Order;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The single most misread thing about ApplicationEventPublisher: publishing is a plain method call.
 * No queue, no thread hand-off, no isolation. SimpleApplicationEventMulticaster only dispatches to
 * an Executor if one was set on it, and nothing sets one by default.
 *
 * <p>Notes: docs/events.md, "The publish path".
 */
@SpringJUnitConfig(SynchronousPublishTest.Config.class)
class SynchronousPublishTest {

    @Autowired
    ApplicationEventPublisher publisher;

    @Autowired
    Recorder recorder;

    @BeforeEach
    void reset() {
        recorder.clear();
    }

    @Test
    void theListenerRunsOnThePublishingThread() {
        String publishingThread = Thread.currentThread().getName();

        publisher.publishEvent(new Payment("p-1", 50));

        assertThat(recorder.threadsFor("listener")).containsExactly(publishingThread);
    }

    @Test
    void publishEventDoesNotReturnUntilEveryListenerHasFinished() {
        recorder.record("before-publish");
        publisher.publishEvent(new Payment("p-2", 50));
        recorder.record("after-publish");

        assertThat(recorder.labels())
                .containsExactly("before-publish", "listener", "slow-listener", "wrapped:Payment", "after-publish");
    }

    @Test
    void aFailingListenerBreaksThePublisherAndSkipsTheListenersBehindIt() {
        // No error handler is configured on the multicaster by default, so the listener exception
        // comes straight back out of publishEvent - into whatever business method published it.
        assertThatThrownBy(() -> publisher.publishEvent(new Poison()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("listener blew up");

        assertThat(recorder.labels())
                .as("the second listener never got a chance to run")
                .containsExactly("poison-first");
    }

    @Test
    void aNonApplicationEventPayloadIsWrappedInAPayloadApplicationEvent() {
        publisher.publishEvent(new Payment("p-3", 50));

        assertThat(recorder.labels()).contains("wrapped:Payment");
    }

    @Test
    void theMulticasterHasNoExecutorWhichIsWhyAllOfTheAboveIsTrue(
            @Autowired SimpleApplicationEventMulticaster multicaster) {
        // multicastEvent() branches on this one field. Null means "call the listener inline".
        // Setting it is what turns every listener in the application async at once - see
        // AsyncListenerTest for the per-listener alternative you almost always want instead.
        assertThat(ReflectionTestUtils.getField(multicaster, "taskExecutor")).isNull();
    }

    record Payment(String id, long amount) {
    }

    record Poison() {
    }

    @Configuration(proxyBeanMethods = false)
    static class Config {

        @Bean
        Recorder recorder() {
            return new Recorder();
        }

        @Bean
        Listeners listeners(Recorder recorder) {
            return new Listeners(recorder);
        }
    }

    static class Listeners {

        private final Recorder recorder;

        Listeners(Recorder recorder) {
            this.recorder = recorder;
        }

        @Order(1)
        @EventListener
        void first(Payment payment) {
            recorder.record("listener");
        }

        @Order(2)
        @EventListener
        void second(Payment payment) {
            recorder.record("slow-listener");
        }

        @Order(3)
        @EventListener
        void observesTheWrapper(PayloadApplicationEvent<?> event) {
            recorder.record("wrapped:" + event.getPayload().getClass().getSimpleName());
        }

        @Order(1)
        @EventListener
        void poisonFirst(Poison event) {
            recorder.record("poison-first");
            throw new IllegalStateException("listener blew up");
        }

        @Order(2)
        @EventListener
        void poisonSecond(Poison event) {
            recorder.record("poison-second");
        }
    }
}
