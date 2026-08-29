package com.caseythecoder.spring.conditions;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Underneath every {@code @ConditionalOnSomething} is one interface with one method. Writing one
 * is the fastest way to stop treating auto-configuration as magic.
 *
 * <p>The two facts to take away: a condition is evaluated while configuration classes are being
 * <em>parsed</em>, so nothing has been instantiated yet; and a condition on a class short-circuits
 * the whole class, so a bean method inside it is never even looked at.
 *
 * <p>Notes: docs/conditions.md, "Writing one".
 */
class CustomConditionTest {

    @Test
    void everyConditionIsEvaluatedBeforeASingleBeanIsConstructed() {
        Trace.reset();

        new ApplicationContextRunner()
                .withUserConfiguration(TracedConfig.class)
                .withPropertyValues("feature.orders.enabled=true")
                .run(context -> {
                    context.getBean("tracedBean");
                    assertThat(Trace.events)
                            .containsExactly("condition evaluated", "bean constructed");
                });
    }

    @Test
    void aFailingConditionOnTheClassMeansTheBeanMethodsInsideAreNeverEvenRead() {
        Trace.reset();

        new ApplicationContextRunner()
                .withUserConfiguration(GatedOuterConfig.class)
                .run(context -> {
                    assertThat(context).doesNotHaveBean("innerBean");
                    assertThat(Trace.events)
                            .as("the inner condition was never asked, because the class was skipped whole")
                            .doesNotContain("inner condition evaluated");
                });
    }

    @Test
    void aConditionCanSeeTheEnvironmentTheBeanFactoryAndTheClassLoader() {
        new ApplicationContextRunner()
                .withUserConfiguration(InspectingConfig.class)
                .run(context -> assertThat(Inspecting.seen)
                        .containsEntry("environment", true)
                        .containsEntry("beanFactory", true)
                        .containsEntry("classLoader", true)
                        .containsEntry("resourceLoader", true));
    }

    @Test
    void conditionalOnClassNamesAMissingClassWithoutEverLoadingIt() {
        // The reason auto-configuration can reference classes you have not put on the classpath:
        // the annotation is read from bytecode by ASM, and the class is only loaded once the
        // condition has already passed. Naming a class that is not there is a "no", not an error.
        new ApplicationContextRunner()
                .withUserConfiguration(OnClassConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean("needsAMissingClass");
                    assertThat(context).hasBean("needsAPresentClass");
                });
    }

    @Test
    void aMetaAnnotationMakesYourOwnConditionReadAsWellAsBootsDo() {
        new ApplicationContextRunner()
                .withUserConfiguration(FeatureConfig.class)
                .run(context -> assertThat(context).doesNotHaveBean("ordersFeature"));

        new ApplicationContextRunner()
                .withUserConfiguration(FeatureConfig.class)
                .withPropertyValues("feature.orders.enabled=true")
                .run(context -> assertThat(context).hasBean("ordersFeature"));
    }

    // ---------------------------------------------------------------------------------------

    /** A shared, deliberately simple trace; conditions run outside any bean, so this is static. */
    static final class Trace {

        static final java.util.List<String> events = new java.util.concurrent.CopyOnWriteArrayList<>();

        static void reset() {
            events.clear();
        }
    }

    /** The whole of the {@link Condition} contract: one method, one boolean. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    @Conditional(OnFeatureCondition.class)
    @interface ConditionalOnFeature {

        String value();
    }

    static class OnFeatureCondition implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            Map<String, Object> attributes =
                    metadata.getAnnotationAttributes(ConditionalOnFeature.class.getName());
            String feature = (String) attributes.get("value");
            return context.getEnvironment().getProperty("feature." + feature + ".enabled", Boolean.class, false);
        }
    }

    static class TracingFeatureCondition extends OnFeatureCondition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            Trace.events.add("condition evaluated");
            return super.matches(context, metadata);
        }
    }

    static class AlwaysFalse implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return false;
        }
    }

    static class TracingInnerCondition implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            Trace.events.add("inner condition evaluated");
            return true;
        }
    }

    static class Inspecting implements Condition {

        static final Map<String, Boolean> seen = new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            seen.put("environment", context.getEnvironment() != null);
            seen.put("beanFactory", context.getBeanFactory() != null);
            seen.put("classLoader", context.getClassLoader() != null);
            seen.put("resourceLoader", context.getResourceLoader() != null);
            return true;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class TracedConfig {

        @Bean
        @Conditional(TracingFeatureCondition.class)
        @ConditionalOnFeature("orders")
        String tracedBean() {
            Trace.events.add("bean constructed");
            return "traced";
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Conditional(AlwaysFalse.class)
    static class GatedOuterConfig {

        @Bean
        @Conditional(TracingInnerCondition.class)
        String innerBean() {
            return "inner";
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Conditional(Inspecting.class)
    static class InspectingConfig {
    }

    @Configuration(proxyBeanMethods = false)
    static class OnClassConfig {

        @Bean
        @ConditionalOnClass(name = "com.example.NotOnTheClasspath")
        String needsAMissingClass() {
            return "never";
        }

        @Bean
        @ConditionalOnClass(name = "java.util.ArrayList")
        String needsAPresentClass() {
            return "always";
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class FeatureConfig {

        @Bean
        @ConditionalOnFeature("orders")
        String ordersFeature() {
            return "orders";
        }
    }
}
