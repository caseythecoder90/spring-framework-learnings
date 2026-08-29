package com.caseythecoder.spring.conditions;

import org.junit.jupiter.api.Test;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @ConditionalOnProperty}, and the one attribute of it that decides whether a feature is
 * opt-in or opt-out: {@code matchIfMissing}.
 *
 * <p>Notes: docs/conditions.md, "Conditions on properties".
 */
class ConditionalOnPropertyTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(Config.class);

    @Test
    void aPlainConditionalOnPropertyIsOptIn() {
        runner.run(context -> assertThat(context).doesNotHaveBean("optIn"));
        runner.withPropertyValues("feature.opt-in.enabled=true")
                .run(context -> assertThat(context).hasBean("optIn"));
    }

    @Test
    void matchIfMissingMakesItOptOut() {
        runner.run(context -> assertThat(context).hasBean("optOut"));
        runner.withPropertyValues("feature.opt-out.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean("optOut"));
    }

    @Test
    void theDefaultHavingValueIsAnythingExceptFalse() {
        // havingValue = "" (the default) means "present and not equal to false", so any of these
        // switch the bean on. It is not a strict equality check against "true".
        for (String value : new String[] {"true", "TRUE", "yes", "on", "banana"}) {
            runner.withPropertyValues("feature.opt-in.enabled=" + value)
                    .run(context -> assertThat(context).hasBean("optIn"));
        }
        runner.withPropertyValues("feature.opt-in.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean("optIn"));
    }

    @Test
    void anExplicitHavingValueIsMatchedExactlyButNotCaseSensitively() {
        runner.withPropertyValues("feature.mode=fast")
                .run(context -> assertThat(context).hasBean("fastMode"));
        runner.withPropertyValues("feature.mode=FAST")
                .run(context -> assertThat(context).hasBean("fastMode"));
        runner.withPropertyValues("feature.mode=slow")
                .run(context -> assertThat(context).doesNotHaveBean("fastMode"));
    }

    @Test
    void aConditionOnTheClassAppliesToEveryBeanMethodInIt() {
        runner.run(context -> assertThat(context).doesNotHaveBean("insideAGatedConfiguration"));
        runner.withPropertyValues("feature.group.enabled=true")
                .run(context -> assertThat(context).hasBean("insideAGatedConfiguration"));
    }

    // ---------------------------------------------------------------------------------------

    @Configuration(proxyBeanMethods = false)
    static class Config {

        @Bean
        @ConditionalOnProperty("feature.opt-in.enabled")
        String optIn() {
            return "opt in";
        }

        @Bean
        @ConditionalOnProperty(name = "feature.opt-out.enabled", matchIfMissing = true)
        String optOut() {
            return "opt out";
        }

        @Bean
        @ConditionalOnProperty(name = "feature.mode", havingValue = "fast")
        String fastMode() {
            return "fast";
        }

        @Configuration(proxyBeanMethods = false)
        @ConditionalOnProperty("feature.group.enabled")
        static class GatedConfiguration {

            @Bean
            String insideAGatedConfiguration() {
                return "gated";
            }
        }
    }
}
