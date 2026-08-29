package com.caseythecoder.spring.lifecycle;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The injection styles and the one thing that separates them in practice: what happens when two
 * beans need each other.
 *
 * <p>Constructor injection cannot participate in a cycle, because neither object can exist before
 * the other. Field and setter injection can, because Spring exposes a half-built instance. That is
 * the entire technical argument for constructor injection; the readability argument is separate and
 * also true.
 *
 * <p>Notes: docs/bean-lifecycle.md, "Injection styles" and "Circular references".
 */
class InjectionAndCyclesTest {

    @Test
    void aSingleConstructorNeedsNoAutowiredAnnotation() {
        try (var context = new AnnotationConfigApplicationContext(SimpleConfig.class)) {
            assertThat(context.getBean(NeedsOne.class).dependency).isNotNull();
        }
    }

    @Test
    void anOptionalDependencyCanBeAbsentWithoutFailing() {
        try (var context = new AnnotationConfigApplicationContext(OptionalConfig.class)) {
            OptionalHolder holder = context.getBean(OptionalHolder.class);

            assertThat(holder.maybe).isEmpty();
            assertThat(holder.provider.getIfAvailable()).isNull();
            assertThat(holder.all).as("an empty list, not null, and not a failure").isEmpty();
        }
    }

    @Test
    void constructorInjectionCannotFormACycle() {
        assertThatThrownBy(() -> new AnnotationConfigApplicationContext(ConstructorCycleConfig.class).close())
                .isInstanceOf(BeanCreationException.class)
                .hasMessageContaining("Is there an unresolvable circular reference");
    }

    @Test
    void fieldInjectionCanFormACycleAndThePlainContainerAllowsIt() {
        // Spring exposes a reference to the half-constructed singleton so the other side can hold
        // it. Both beans end up wired, one of them having been handed an object that was not yet
        // finished at the time.
        try (var context = new AnnotationConfigApplicationContext(FieldCycleConfig.class)) {
            FieldA a = context.getBean(FieldA.class);
            FieldB b = context.getBean(FieldB.class);

            assertThat(a.b).isSameAs(b);
            assertThat(b.a).isSameAs(a);
        }
    }

    @Test
    void turningOffEarlyExposureRejectsTheCycle() {
        // This is what Spring Boot does by default: spring.main.allow-circular-references=false
        // since 2.6, which turns a working-but-fragile application into a startup failure.
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(FieldCycleConfig.class);
            ((DefaultListableBeanFactory) context.getBeanFactory()).setAllowCircularReferences(false);

            assertThatThrownBy(context::refresh)
                    .isInstanceOf(BeanCreationException.class)
                    .hasMessageContaining("Is there an unresolvable circular reference");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class SimpleConfig {

        @Bean
        Dependency dependency() {
            return new Dependency();
        }

        @Bean
        NeedsOne needsOne(Dependency dependency) {
            return new NeedsOne(dependency);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class OptionalConfig {

        @Bean
        OptionalHolder optionalHolder() {
            return new OptionalHolder();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ConstructorCycleConfig {

        @Bean
        CtorA ctorA(CtorB b) {
            return new CtorA(b);
        }

        @Bean
        CtorB ctorB(CtorA a) {
            return new CtorB(a);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class FieldCycleConfig {

        @Bean
        FieldA fieldA() {
            return new FieldA();
        }

        @Bean
        FieldB fieldB() {
            return new FieldB();
        }
    }

    static class Dependency {
    }

    static class NeedsOne {

        final Dependency dependency;

        NeedsOne(Dependency dependency) {
            this.dependency = dependency;
        }
    }

    static class OptionalHolder {

        @Autowired(required = false)
        Optional<Dependency> maybe = Optional.empty();

        @Autowired
        ObjectProvider<Dependency> provider;

        @Autowired(required = false)
        List<Dependency> all = List.of();
    }

    static class CtorA {

        CtorA(CtorB b) {
        }
    }

    static class CtorB {

        CtorB(CtorA a) {
        }
    }

    static class FieldA {

        @Autowired
        FieldB b;
    }

    static class FieldB {

        @Autowired
        FieldA a;
    }
}
