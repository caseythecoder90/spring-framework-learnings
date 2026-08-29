package com.caseythecoder.spring.binding;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.annotation.Value;
import java.util.Map;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Relaxed binding: the reason {@code my.service.read-timeout},
 * {@code my.service.readTimeout} and {@code MY_SERVICE_READTIMEOUT} all reach the same field.
 *
 * <p>The part that surprises people is where it stops. Relaxed binding is a feature of the
 * <em>Binder</em>, which is to say of {@code @ConfigurationProperties}. {@code @Value} does not go
 * through the Binder at all - it is a plain placeholder lookup, and it matches the property name
 * exactly.
 *
 * <p>Notes: docs/property-binding.md, "Relaxed binding, and where it stops".
 */
class RelaxedBindingTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(PropertiesConfig.class);

    @Test
    void kebabCaseIsTheCanonicalForm() {
        runner.withPropertyValues("my.service.read-timeout=5")
                .run(context -> assertThat(context.getBean(ServiceProperties.class).getReadTimeout()).isEqualTo(5));
    }

    @Test
    void camelCaseBindsToTheSameField() {
        runner.withPropertyValues("my.service.readTimeout=5")
                .run(context -> assertThat(context.getBean(ServiceProperties.class).getReadTimeout()).isEqualTo(5));
    }

    @Test
    void underscoresBindToTheSameField() {
        runner.withPropertyValues("my.service.read_timeout=5")
                .run(context -> assertThat(context.getBean(ServiceProperties.class).getReadTimeout()).isEqualTo(5));
    }

    @Test
    void upperCaseWithUnderscoresBindsOnlyFromAnEnvironmentVariableSource() {
        // The form an environment variable arrives in, and the one place the mapping is not done
        // by the binder alone: SystemEnvironmentPropertyMapper is only applied to a property
        // source that says it holds environment variables. The same key in an ordinary
        // MapPropertySource binds to nothing.
        runner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource("systemEnvironment",
                                Map.of("MY_SERVICE_READTIMEOUT", "5"))))
                .run(context -> assertThat(context.getBean(ServiceProperties.class).getReadTimeout()).isEqualTo(5));

        runner.withPropertyValues("MY_SERVICE_READTIMEOUT=5")
                .run(context -> assertThat(context.getBean(ServiceProperties.class).getReadTimeout())
                        .as("same key, ordinary property source, no mapping")
                        .isEqualTo(0));
    }

    @Test
    void valueDoesNotGetRelaxedBindingAndFailsOnTheWrongSpelling() {
        // @Value is a placeholder lookup, not a binding. The property below is spelled
        // read-timeout in the environment and readTimeout in the annotation, and that is the end
        // of it: no relaxation, no default, a failed context.
        new ApplicationContextRunner()
                .withUserConfiguration(ValueConfig.class)
                .withPropertyValues("my.service.read-timeout=5")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .isInstanceOf(BeanCreationException.class)
                        .hasStackTraceContaining("Could not resolve placeholder 'my.service.readTimeout'"));
    }

    @Test
    void valueWorksWhenTheNameMatchesExactly() {
        new ApplicationContextRunner()
                .withUserConfiguration(ValueConfig.class)
                .withPropertyValues("my.service.readTimeout=5")
                .run(context -> assertThat(context.getBean(UsesValue.class).readTimeout).isEqualTo(5));
    }

    @Test
    void anUnsetPropertyLeavesTheFieldAtItsJavaDefaultRatherThanFailing() {
        // The other asymmetry: a missing @Value blows the context up, a missing bound property
        // does not. Silence is the default for @ConfigurationProperties.
        runner.run(context ->
                assertThat(context.getBean(ServiceProperties.class).getReadTimeout()).isEqualTo(0));
    }

    // ---------------------------------------------------------------------------------------

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ServiceProperties.class)
    static class PropertiesConfig {
    }

    @ConfigurationProperties(prefix = "my.service")
    static class ServiceProperties {

        private int readTimeout;

        public int getReadTimeout() {
            return this.readTimeout;
        }

        public void setReadTimeout(int readTimeout) {
            this.readTimeout = readTimeout;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ValueConfig {

        @Bean
        static PropertySourcesPlaceholderConfigurer placeholders() {
            return new PropertySourcesPlaceholderConfigurer();
        }

        @Bean
        UsesValue usesValue(@Value("${my.service.readTimeout}") int readTimeout) {
            return new UsesValue(readTimeout);
        }
    }

    static class UsesValue {

        final int readTimeout;

        UsesValue(int readTimeout) {
            this.readTimeout = readTimeout;
        }
    }
}
