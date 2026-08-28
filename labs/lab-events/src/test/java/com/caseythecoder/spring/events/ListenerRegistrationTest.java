package com.caseythecoder.spring.events;

import java.util.List;

import com.caseythecoder.spring.support.Recorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.AnnotationConfigUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.context.event.EventListenerMethodProcessor;
import org.springframework.core.annotation.Order;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How an annotated method becomes a listener, and the three behaviours of the adapter that people
 * discover by accident: ordering, SpEL conditions, and return values being republished as events.
 *
 * <p>Notes: docs/events.md, "From annotation to listener".
 */
@SpringJUnitConfig(ListenerRegistrationTest.Config.class)
class ListenerRegistrationTest {

    @Autowired
    ApplicationContext context;

    @Autowired
    ApplicationEventPublisher publisher;

    @Autowired
    Recorder recorder;

    @BeforeEach
    void reset() {
        recorder.clear();
    }

    @Test
    void annotatedMethodsAreTurnedIntoListenersByOneInfrastructureBean() {
        // EventListenerMethodProcessor is a SmartInitializingSingleton: once every singleton
        // exists it walks getBeanNamesForType(Object.class) and adapts each @EventListener method.
        // It works off bean DEFINITIONS, not instances, and ApplicationListenerMethodAdapter calls
        // getBean(beanName) only when an event actually arrives - so a @Lazy bean still gets its
        // listener registered, and is instantiated by the first matching event. The flip side: on
        // a prototype-scoped bean that lookup yields a brand new instance per event.
        assertThat(context.getBean(AnnotationConfigUtils.EVENT_LISTENER_PROCESSOR_BEAN_NAME))
                .isInstanceOf(EventListenerMethodProcessor.class);
        assertThat(context.containsBean(AnnotationConfigUtils.EVENT_LISTENER_FACTORY_BEAN_NAME)).isTrue();
    }

    @Test
    void orderControlsListenerSequenceForOneEvent() {
        publisher.publishEvent(new Ordered());

        assertThat(recorder.labels()).containsExactly("first", "second", "third");
    }

    @Test
    void aConditionIsEvaluatedAgainstTheEventBeforeTheMethodIsCalled() {
        publisher.publishEvent(new Payment("small", 50));
        assertThat(recorder.labels()).doesNotContain("large-payment");

        publisher.publishEvent(new Payment("large", 5000));
        assertThat(recorder.labels()).contains("large-payment");
    }

    @Test
    void aNonNullReturnValueIsPublishedAsAFollowUpEvent() {
        publisher.publishEvent(new Chained("start"));

        assertThat(recorder.labels())
                .as("the first listener returned a Step, which Spring published for the second")
                .containsExactly("chain-source", "chain-step:start");
    }

    @Test
    void aCollectionReturnValueFansOutIntoOneEventPerElement() {
        publisher.publishEvent(new Fanout());

        assertThat(recorder.labels()).containsExactly("fanout-source", "chain-step:a", "chain-step:b");
    }

    record Ordered() {
    }

    record Payment(String id, long amount) {
    }

    record Chained(String name) {
    }

    record Fanout() {
    }

    record Step(String name) {
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

        @Order(10)
        @EventListener
        void second(Ordered event) {
            recorder.record("second");
        }

        @Order(1)
        @EventListener
        void first(Ordered event) {
            recorder.record("first");
        }

        @Order(100)
        @EventListener
        void third(Ordered event) {
            recorder.record("third");
        }

        @EventListener(condition = "#payment.amount() > 100")
        void onLargePayment(Payment payment) {
            recorder.record("large-payment");
        }

        /** A returned object is republished. Convenient, and a very easy way to build a loop. */
        @EventListener
        Step onChained(Chained event) {
            recorder.record("chain-source");
            return new Step(event.name());
        }

        @EventListener
        List<Step> onFanout(Fanout event) {
            recorder.record("fanout-source");
            return List.of(new Step("a"), new Step("b"));
        }

        @EventListener
        void onStep(Step step) {
            recorder.record("chain-step:" + step.name());
        }
    }
}
