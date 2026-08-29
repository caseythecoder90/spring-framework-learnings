package com.caseythecoder.spring.startup;

import com.caseythecoder.spring.support.Recorder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.junit.jupiter.api.Test;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.context.ApplicationListener;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.ContextRefreshedEvent;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code AbstractApplicationContext.refresh()} is twelve numbered lines of source, and this is what
 * they look like from the outside: one bean of every kind that gets a say, recorded in the order
 * the container actually calls them.
 *
 * <p>Written by running it and reading the output. The two orderings most often remembered
 * backwards are at the ends: bean definitions can still be edited long after they were registered,
 * and {@code ContextRefreshedEvent} arrives <em>after</em> every {@code SmartLifecycle} has already
 * started, not before.
 *
 * <p>Notes: docs/startup.md, "The order".
 */
class RefreshOrderTest {

    @Test
    void everyStartupHookFiresInThisOrder() {
        Recorder recorder = new Recorder();
        Config.recorder = recorder;

        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.register(Config.class);
        context.refresh();
        recorder.record("--- refresh() returned ---");
        context.close();

        assertThat(recorder.labels()).containsExactly(
                // 1. Bean definitions can still be added at this point. This is where
                //    @Configuration classes are parsed, by ConfigurationClassPostProcessor.
                "BeanDefinitionRegistryPostProcessor: registry",
                "BeanDefinitionRegistryPostProcessor: beanFactory",
                // 2. Definitions are fixed; their contents are not. Placeholder resolution happens
                //    here, in PropertySourcesPlaceholderConfigurer.
                "BeanFactoryPostProcessor",
                // 3. Only now are BeanPostProcessors registered, which is why anything instantiated
                //    during step 2 misses out on them entirely.
                "BeanPostProcessor: constructed",
                // 4. Singletons, one at a time, each one fully through the lifecycle.
                "bean: constructed",
                "bean: @PostConstruct",
                "BeanPostProcessor: afterInitialization for regularBean",
                // 5. Everything exists. The hook for "now that the container is populated".
                "SmartInitializingSingleton",
                // 6. finishRefresh(): lifecycle beans start...
                "SmartLifecycle: start",
                // 7. ...and only then is the event published.
                "ContextRefreshedEvent",
                "--- refresh() returned ---",
                // close() runs the tail in the mirror image, with one twist: the event comes first,
                // so a listener can still use every bean in the context.
                "ContextClosedEvent",
                "SmartLifecycle: stop",
                "bean: @PreDestroy");
    }

    @Test
    void beanPostProcessorsAreRegisteredAfterBeanFactoryPostProcessorsHaveRun() {
        Recorder recorder = new Recorder();
        Config.recorder = recorder;

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(Config.class);
            context.refresh();
        }

        assertThat(recorder.recordedBefore("BeanFactoryPostProcessor", "BeanPostProcessor: constructed"))
                .as("the whole reason an eagerly created bean escapes post-processing")
                .isTrue();
    }

    @Test
    void theContextRefreshedEventIsTheLastThingRefreshDoes() {
        Recorder recorder = new Recorder();
        Config.recorder = recorder;

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(Config.class);
            context.refresh();
        }

        assertThat(recorder.recordedBefore("SmartLifecycle: start", "ContextRefreshedEvent"))
                .as("a ContextRefreshedEvent listener can assume every SmartLifecycle is already running")
                .isTrue();
    }

    // ---------------------------------------------------------------------------------------

    @Configuration(proxyBeanMethods = false)
    static class Config {

        /** Static, so the recorder is reachable from the post-processors before any bean exists. */
        static Recorder recorder;

        /**
         * Both post-processor beans are {@code static}. A non-static one forces its enclosing
         * {@code @Configuration} class to be instantiated during step 2, before any
         * {@code BeanPostProcessor} exists - see {@code EagerInstantiationTest}.
         */
        @Bean
        static BeanDefinitionRegistryPostProcessor registryPostProcessor() {
            return new BeanDefinitionRegistryPostProcessor() {

                @Override
                public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
                    recorder.record("BeanDefinitionRegistryPostProcessor: registry");
                }

                @Override
                public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
                    recorder.record("BeanDefinitionRegistryPostProcessor: beanFactory");
                }
            };
        }

        @Bean
        static BeanFactoryPostProcessor beanFactoryPostProcessor() {
            return beanFactory -> recorder.record("BeanFactoryPostProcessor");
        }

        @Bean
        static BeanPostProcessor beanPostProcessor() {
            recorder.record("BeanPostProcessor: constructed");
            return new BeanPostProcessor() {

                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                    if (bean instanceof RegularBean) {
                        recorder.record("BeanPostProcessor: afterInitialization for " + beanName);
                    }
                    return bean;
                }
            };
        }

        @Bean
        RegularBean regularBean() {
            return new RegularBean(recorder);
        }

        @Bean
        SmartInitializingSingleton smartInitializingSingleton() {
            return () -> recorder.record("SmartInitializingSingleton");
        }

        @Bean
        SmartLifecycle lifecycleBean() {
            return new RecordingLifecycle(recorder, "SmartLifecycle", 0);
        }

        @Bean
        ApplicationListener<ContextRefreshedEvent> refreshedListener() {
            return event -> recorder.record("ContextRefreshedEvent");
        }

        @Bean
        ApplicationListener<ContextClosedEvent> closedListener() {
            return event -> recorder.record("ContextClosedEvent");
        }
    }

    static class RegularBean {

        private final Recorder recorder;

        RegularBean(Recorder recorder) {
            this.recorder = recorder;
            recorder.record("bean: constructed");
        }

        @PostConstruct
        void started() {
            recorder.record("bean: @PostConstruct");
        }

        @PreDestroy
        void stopping() {
            recorder.record("bean: @PreDestroy");
        }
    }
}
