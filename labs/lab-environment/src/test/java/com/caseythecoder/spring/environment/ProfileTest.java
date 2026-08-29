package com.caseythecoder.spring.environment;

import org.junit.jupiter.api.Test;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @Profile} is a {@code @Conditional} with a nicer name, evaluated while
 * {@code @Configuration} classes are being parsed - which is to say, long before any bean exists.
 *
 * <p>Notes: docs/environment.md, "Profiles".
 */
class ProfileTest {

    @Test
    void aProfileBeanExistsOnlyWhenItsProfileIsActive() {
        assertThat(beansWith("staging")).contains("stagingOnly").doesNotContain("productionOnly");
        assertThat(beansWith("production")).contains("productionOnly").doesNotContain("stagingOnly");
    }

    @Test
    void aNegatedProfileIsTheDefaultCase() {
        assertThat(beansWith()).as("no profiles active").contains("notProduction");
        assertThat(beansWith("staging")).contains("notProduction");
        assertThat(beansWith("production")).doesNotContain("notProduction");
    }

    @Test
    void profileExpressionsSupportAndOrAndBrackets() {
        assertThat(beansWith("staging")).doesNotContain("stagingInEurope");
        assertThat(beansWith("eu")).doesNotContain("stagingInEurope");
        assertThat(beansWith("staging", "eu")).contains("stagingInEurope");
    }

    @Test
    void withNoActiveProfileTheDefaultProfileIsTheOneThatApplies() {
        // spring.profiles.default is "default" unless you change it, and a bean annotated
        // @Profile("default") exists exactly when nothing else is active. Setting any profile at
        // all switches it off, which is a common surprise on a first deployment.
        assertThat(beansWith()).contains("defaultProfileBean");
        assertThat(beansWith("staging")).doesNotContain("defaultProfileBean");
    }

    private static java.util.List<String> beansWith(String... profiles) {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles(profiles);
            context.register(Config.class);
            context.refresh();
            return java.util.List.of(context.getBeanDefinitionNames());
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Config {

        @Bean
        @Profile("staging")
        String stagingOnly() {
            return "staging";
        }

        @Bean
        @Profile("production")
        String productionOnly() {
            return "production";
        }

        @Bean
        @Profile("!production")
        String notProduction() {
            return "not production";
        }

        @Bean
        @Profile("staging & eu")
        String stagingInEurope() {
            return "staging in europe";
        }

        @Bean
        @Profile("default")
        String defaultProfileBean() {
            return "default";
        }
    }
}
