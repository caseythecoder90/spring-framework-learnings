package com.caseythecoder.spring.lifecycle;

import java.util.concurrent.atomic.AtomicInteger;

import com.caseythecoder.spring.support.Recorder;
import jakarta.annotation.PreDestroy;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Scope;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scope decides how many instances exist and who is responsible for destroying them, and
 * {@code @Lazy} decides when the first one appears. Both change which lifecycle callbacks you get.
 *
 * <p>Notes: docs/bean-lifecycle.md, "Scopes" and "BeanPostProcessor ordering".
 */
class ScopeAndLazyTest {

    @Test
    void singletonsAreCreatedOnceAtStartup() {
        Counters.reset();
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            assertThat(Counters.singleton.get())
                    .as("created during refresh, before anyone asked for it")
                    .isEqualTo(1);

            assertThat(context.getBean(SingletonBean.class))
                    .isSameAs(context.getBean(SingletonBean.class));
            assertThat(Counters.singleton.get()).isEqualTo(1);
        }
    }

    @Test
    void aLazyBeanIsNotCreatedUntilSomethingAsks() {
        Counters.reset();
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            assertThat(Counters.lazy.get()).isZero();

            context.getBean(LazyBean.class);
            assertThat(Counters.lazy.get()).isEqualTo(1);
        }
    }

    @Test
    void everyLookupOfAPrototypeIsANewInstance() {
        Counters.reset();
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            assertThat(Counters.prototype.get()).as("prototypes are never eager").isZero();

            var first = context.getBean(PrototypeBean.class);
            var second = context.getBean(PrototypeBean.class);

            assertThat(first).isNotSameAs(second);
            assertThat(Counters.prototype.get()).isEqualTo(2);
        }
    }

    @Test
    void theContainerNeverDestroysAPrototype() {
        Counters.reset();
        Recorder recorder = new Recorder();
        Config.recorder = recorder;

        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            context.getBean(PrototypeBean.class);
            context.getBean(SingletonBean.class);
        }

        assertThat(recorder.labels())
                .as("Spring stops tracking a prototype the moment it hands it over — "
                        + "anything the bean holds open is yours to close")
                .containsExactly("singleton destroyed");
    }

    @Test
    void aPrototypeInjectedIntoASingletonIsResolvedExactlyOnce() {
        // The classic surprise. The singleton is built once, so its dependency is resolved once,
        // and the "prototype" behaves like a singleton for the rest of the run.
        Counters.reset();
        try (var context = new AnnotationConfigApplicationContext(HolderConfig.class)) {
            Holder holder = context.getBean(Holder.class);

            // One instance, created while the singleton was being built, and reused forever after.
            assertThat(holder.injectedOnce).isSameAs(holder.injectedOnce);
            assertThat(Counters.prototype.get()).isEqualTo(1);

            // ObjectProvider defers the lookup to call time, which is the fix.
            assertThat(holder.provider.getObject()).isNotSameAs(holder.provider.getObject());
            assertThat(Counters.prototype.get()).isEqualTo(3);
        }
    }

    @Test
    void beanPostProcessorsRunInPriorityThenOrderThenRegistrationOrder() {
        Recorder recorder = new Recorder();
        Config.recorder = recorder;
        Counters.reset();

        try (var context = new AnnotationConfigApplicationContext(OrderingConfig.class)) {
            context.getBean(SingletonBean.class);
        }

        assertThat(recorder.labels())
                .filteredOn(label -> label.startsWith("bpp:"))
                .containsExactly("bpp:priority", "bpp:ordered", "bpp:unordered");
    }

    static final class Counters {

        static final AtomicInteger singleton = new AtomicInteger();

        static final AtomicInteger lazy = new AtomicInteger();

        static final AtomicInteger prototype = new AtomicInteger();

        static void reset() {
            singleton.set(0);
            lazy.set(0);
            prototype.set(0);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Config {

        static Recorder recorder = new Recorder();

        @Bean
        SingletonBean singletonBean() {
            return new SingletonBean();
        }

        @Bean
        @Lazy
        LazyBean lazyBean() {
            return new LazyBean();
        }

        @Bean
        @Scope("prototype")
        PrototypeBean prototypeBean() {
            return new PrototypeBean();
        }

    }

    @Configuration(proxyBeanMethods = false)
    static class HolderConfig {

        @Bean
        @Scope("prototype")
        PrototypeBean prototypeBean() {
            return new PrototypeBean();
        }

        @Bean
        Holder holder(PrototypeBean injectedOnce, ObjectProvider<PrototypeBean> provider) {
            return new Holder(injectedOnce, provider);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class OrderingConfig {

        @Bean
        SingletonBean singletonBean() {
            return new SingletonBean();
        }

        @Bean
        static BeanPostProcessor unordered() {
            return named("bpp:unordered");
        }

        @Bean
        static PriorityOrderedProcessor priorityOrdered() {
            return new PriorityOrderedProcessor();
        }

        @Bean
        static OrderedProcessor ordered() {
            return new OrderedProcessor();
        }

        private static BeanPostProcessor named(String label) {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessBeforeInitialization(Object bean, String beanName) {
                    if (bean instanceof SingletonBean) {
                        Config.recorder.record(label);
                    }
                    return bean;
                }
            };
        }
    }

    static class PriorityOrderedProcessor implements BeanPostProcessor, PriorityOrdered {

        @Override
        public Object postProcessBeforeInitialization(Object bean, String beanName) {
            if (bean instanceof SingletonBean) {
                Config.recorder.record("bpp:priority");
            }
            return bean;
        }

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }
    }

    static class OrderedProcessor implements BeanPostProcessor, Ordered {

        @Override
        public Object postProcessBeforeInitialization(Object bean, String beanName) {
            if (bean instanceof SingletonBean) {
                Config.recorder.record("bpp:ordered");
            }
            return bean;
        }

        @Override
        public int getOrder() {
            return 0;
        }
    }

    static class SingletonBean {

        SingletonBean() {
            Counters.singleton.incrementAndGet();
        }

        @PreDestroy
        void close() {
            Config.recorder.record("singleton destroyed");
        }
    }

    static class LazyBean {

        LazyBean() {
            Counters.lazy.incrementAndGet();
        }
    }

    static class PrototypeBean {

        PrototypeBean() {
            Counters.prototype.incrementAndGet();
        }

        @PreDestroy
        void close() {
            Config.recorder.record("prototype destroyed");
        }
    }

    static class Holder {

        final PrototypeBean injectedOnce;

        final ObjectProvider<PrototypeBean> provider;

        Holder(PrototypeBean injectedOnce, ObjectProvider<PrototypeBean> provider) {
            this.injectedOnce = injectedOnce;
            this.provider = provider;
        }
    }
}
