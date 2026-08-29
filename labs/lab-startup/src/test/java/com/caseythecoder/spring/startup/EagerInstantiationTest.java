package com.caseythecoder.spring.startup;

import com.caseythecoder.spring.support.Recorder;
import org.junit.jupiter.api.Test;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one startup ordering bug that actually costs people days: a bean created during the
 * {@code BeanFactoryPostProcessor} phase misses every {@code BeanPostProcessor}, because they have
 * not been registered yet.
 *
 * <p>Missing a {@code BeanPostProcessor} means missing the auto-proxy creator, which means the bean
 * is never proxied, which means its {@code @Transactional}, {@code @Async}, {@code @Cacheable} and
 * {@code @Retryable} annotations do nothing. In production. Silently. The only trace is one INFO
 * line: <em>"... is not eligible for getting processed by all BeanPostProcessors"</em>.
 *
 * <p>Notes: docs/startup.md, "Instantiated too early".
 */
class EagerInstantiationTest {

    @Test
    void aBeanPulledOutDuringTheBeanFactoryPostProcessorPhaseIsNeverPostProcessed() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(PullsABeanTooEarly.class);
            context.refresh();

            assertThat(context.getBean("earlyBean", Taggable.class).tagged)
                    .as("created before the BeanPostProcessor existed, so nothing ever saw it")
                    .isFalse();
            assertThat(context.getBean("normalBean", Taggable.class).tagged)
                    .as("created in preInstantiateSingletons, like everything else")
                    .isTrue();
        }
    }

    @Test
    void aNonStaticBeanMethodReturningAPostProcessorDragsItsConfigurationClassUpWithIt() {
        // The reason the Spring team writes @Bean methods returning BeanFactoryPostProcessor or
        // BeanPostProcessor as static: the container has to instantiate the @Configuration class
        // to call the method, and at that point no BeanPostProcessor exists yet. Everything that
        // class injects comes along too.
        Recorder recorder = new Recorder();
        NonStaticFactoryMethod.recorder = recorder;

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(NonStaticFactoryMethod.class);
            context.refresh();
        }

        assertThat(recorder.recordedBefore("@Configuration constructed", "BeanPostProcessor constructed"))
                .as("the configuration class was built during the BeanFactoryPostProcessor phase")
                .isTrue();
    }

    @Test
    void makingTheSameMethodStaticKeepsTheConfigurationClassOutOfIt() {
        Recorder recorder = new Recorder();
        StaticFactoryMethod.recorder = recorder;

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(StaticFactoryMethod.class);
            context.refresh();
        }

        assertThat(recorder.recordedBefore("BeanPostProcessor constructed", "@Configuration constructed"))
                .as("a static @Bean method needs no instance, so the class is built at the normal time")
                .isTrue();
    }

    // ---------------------------------------------------------------------------------------

    /** Something a BeanPostProcessor can leave a mark on. */
    static class Taggable {

        boolean tagged;
    }

    @Configuration(proxyBeanMethods = false)
    static class PullsABeanTooEarly {

        @Bean
        static BeanFactoryPostProcessor tooEager() {
            // Exactly the shape of a real one: a post-processor that wants to look at a bean
            // rather than at its definition.
            return beanFactory -> beanFactory.getBean("earlyBean");
        }

        @Bean
        static BeanPostProcessor tagger() {
            return new BeanPostProcessor() {

                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                    if (bean instanceof Taggable taggable) {
                        taggable.tagged = true;
                    }
                    return bean;
                }
            };
        }

        @Bean
        Taggable earlyBean() {
            return new Taggable();
        }

        @Bean
        Taggable normalBean() {
            return new Taggable();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class NonStaticFactoryMethod {

        static Recorder recorder;

        NonStaticFactoryMethod() {
            recorder.record("@Configuration constructed");
        }

        @Bean
        BeanFactoryPostProcessor notStatic() {
            return beanFactory -> {
            };
        }

        @Bean
        static BeanPostProcessor marker() {
            recorder.record("BeanPostProcessor constructed");
            return new BeanPostProcessor() {
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class StaticFactoryMethod {

        static Recorder recorder;

        StaticFactoryMethod() {
            recorder.record("@Configuration constructed");
        }

        @Bean
        static BeanFactoryPostProcessor isStatic() {
            return beanFactory -> {
            };
        }

        @Bean
        static BeanPostProcessor marker() {
            recorder.record("BeanPostProcessor constructed");
            return new BeanPostProcessor() {
            };
        }
    }
}
