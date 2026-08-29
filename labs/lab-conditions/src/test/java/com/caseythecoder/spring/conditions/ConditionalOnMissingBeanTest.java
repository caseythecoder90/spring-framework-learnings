package com.caseythecoder.spring.conditions;

import org.junit.jupiter.api.Test;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How "Boot backs off when you define your own" actually works, and why the same annotation is a
 * trap in your own code.
 *
 * <p>{@code @ConditionalOnMissingBean} asks a question about the bean factory <em>at the moment the
 * condition is evaluated</em>, which is while configuration classes are being parsed. Its answer
 * therefore depends entirely on what has been registered so far. Auto-configuration gets away with
 * it because it is deliberately processed last and sorted; two of your own
 * {@code @Configuration} classes are not.
 *
 * <p>Notes: docs/conditions.md, "Why auto-configuration can use it and you cannot".
 */
class ConditionalOnMissingBeanTest {

    @Test
    void anAutoConfigurationBacksOffWhenTheApplicationDefinesItsOwnBean() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(GreeterAutoConfiguration.class))
                .withUserConfiguration(UserGreeterConfig.class)
                .run(context -> assertThat(context.getBean(Greeter.class).name()).isEqualTo("yours"));
    }

    @Test
    void andSuppliesTheDefaultWhenTheApplicationDoesNot() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(GreeterAutoConfiguration.class))
                .run(context -> assertThat(context.getBean(Greeter.class).name()).isEqualTo("auto"));
    }

    @Test
    void autoConfigureAfterDecidesWhichOfTwoDefaultsWins() {
        // FallbackAutoConfiguration says it runs after PrimaryAutoConfiguration, so by the time its
        // @ConditionalOnMissingBean is evaluated the primary bean is already registered.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(FallbackAutoConfiguration.class, PrimaryAutoConfiguration.class))
                .run(context -> assertThat(context.getBean(Greeter.class).name()).isEqualTo("primary"));

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(FallbackAutoConfiguration.class))
                .run(context -> assertThat(context.getBean(Greeter.class).name()).isEqualTo("fallback"));
    }

    @Test
    void betweenTwoOfYourOwnConfigurationClassesTheAnswerDependsOnRegistrationOrder() {
        // Same two classes, same annotations, opposite results. Nothing sorts user configuration,
        // so @ConditionalOnMissingBean here is a coin toss that happens to land the same way right
        // up until somebody adds a component scan or renames a package.
        assertThat(greetersWith(ConditionalConfig.class, UnconditionalConfig.class))
                .as("the conditional class was parsed first and saw nothing, so both beans exist "
                        + "and injecting a Greeter now fails with NoUniqueBeanDefinitionException")
                .containsExactlyInAnyOrder("conditionalGreeter", "unconditionalGreeter");

        assertThat(greetersWith(UnconditionalConfig.class, ConditionalConfig.class))
                .as("same classes, other order, and the condition actually backs off")
                .containsExactly("unconditionalGreeter");
    }

    @Test
    void conditionalOnBeanHasTheSameHazardInTheOtherDirection() {
        assertThat(beanNamesWith(RequiresGreeterConfig.class, UnconditionalConfig.class))
                .as("nothing registered yet, so the condition failed")
                .doesNotContain("greeterDependant");

        assertThat(beanNamesWith(UnconditionalConfig.class, RequiresGreeterConfig.class))
                .contains("greeterDependant");
    }

    @Test
    void theConditionIsAboutBeanDefinitionsNotAboutInstances() {
        // Nothing is instantiated during condition evaluation. @ConditionalOnMissingBean is a
        // question about the bean *definitions* registered so far, which is why it can run before
        // any singleton exists at all.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(GreeterAutoConfiguration.class))
                .withUserConfiguration(LazyUserGreeterConfig.class)
                .run(context -> assertThat(context.getBeanNamesForType(Greeter.class))
                        .containsExactly("lazyGreeter"));
    }

    // ---------------------------------------------------------------------------------------

    private static java.util.List<String> greetersWith(Class<?>... configurations) {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(configurations);
            context.refresh();
            return java.util.List.of(context.getBeanNamesForType(Greeter.class));
        }
    }

    private static java.util.List<String> beanNamesWith(Class<?>... configurations) {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(configurations);
            context.refresh();
            return java.util.List.of(context.getBeanDefinitionNames());
        }
    }

    record Greeter(String name) {
    }

    @AutoConfiguration
    static class GreeterAutoConfiguration {

        @Bean
        @ConditionalOnMissingBean
        Greeter greeter() {
            return new Greeter("auto");
        }
    }

    @AutoConfiguration
    static class PrimaryAutoConfiguration {

        @Bean
        @ConditionalOnMissingBean
        Greeter primaryGreeter() {
            return new Greeter("primary");
        }
    }

    @AutoConfiguration(after = PrimaryAutoConfiguration.class)
    static class FallbackAutoConfiguration {

        @Bean
        @ConditionalOnMissingBean
        Greeter fallbackGreeter() {
            return new Greeter("fallback");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UserGreeterConfig {

        @Bean
        Greeter greeter() {
            return new Greeter("yours");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class LazyUserGreeterConfig {

        @Bean
        @org.springframework.context.annotation.Lazy
        Greeter lazyGreeter() {
            return new Greeter("lazy");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ConditionalConfig {

        @Bean
        @ConditionalOnMissingBean
        Greeter conditionalGreeter() {
            return new Greeter("conditional");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UnconditionalConfig {

        @Bean
        Greeter unconditionalGreeter() {
            return new Greeter("unconditional");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class RequiresGreeterConfig {

        @Bean
        @ConditionalOnBean(Greeter.class)
        String greeterDependant() {
            return "needs a greeter";
        }
    }
}
