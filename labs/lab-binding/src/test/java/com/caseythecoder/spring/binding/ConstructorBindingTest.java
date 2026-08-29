package com.caseythecoder.spring.binding;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Constructor binding, which since Boot 3 is what you get by default for any
 * {@code @ConfigurationProperties} type with a single parameterised constructor - a record being
 * the obvious case.
 *
 * <p>Configuration is the one part of an application that is read once at startup and never
 * changes, so it is the one part where immutability costs nothing. The reason to know the rules
 * below is that the two binding styles have genuinely different behaviour for defaults and for
 * missing nested objects, and the switch between them is implicit.
 *
 * <p>Notes: docs/property-binding.md, "Constructor binding".
 */
class ConstructorBindingTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(Config.class);

    @Test
    void aRecordBindsThroughItsConstructorWithNoAnnotationBeyondTheOne() {
        runner.withPropertyValues("client.url=https://example.test", "client.retries=3")
                .run(context -> {
                    ClientProperties properties = context.getBean(ClientProperties.class);
                    assertThat(properties.url()).isEqualTo("https://example.test");
                    assertThat(properties.retries()).isEqualTo(3);
                });
    }

    @Test
    void defaultValueSuppliesWhatTheEnvironmentDoesNot() {
        runner.withPropertyValues("client.url=https://example.test")
                .run(context -> assertThat(context.getBean(ClientProperties.class).retries()).isEqualTo(2));
    }

    @Test
    void aMissingValueWithNoDefaultIsNullOrZeroRatherThanAFailure() {
        // Worth being deliberate about: binding does not require anything. A URL nobody set is
        // null, and the failure happens later, somewhere less helpful. See ConversionTest for
        // the @Validated fix.
        runner.run(context -> assertThat(context.getBean(ClientProperties.class).url()).isNull());
    }

    @Test
    void aNestedObjectIsNullWhenNothingUnderItIsSetAndDefaultValueIsMissing() {
        runner.run(context -> assertThat(context.getBean(ClientProperties.class).proxy()).isNull());
    }

    @Test
    void markingTheNestedObjectDefaultValueBuildsItFromItsOwnDefaults() {
        // @DefaultValue on a nested record means "construct it even if no property under it is
        // set", which is what stops every nested config object needing a null check.
        runner.run(context -> {
            ClientProperties.Timeouts timeouts = context.getBean(ClientProperties.class).timeouts();
            assertThat(timeouts).isNotNull();
            assertThat(timeouts.connect()).isEqualTo(1000);
        });
    }

    @Test
    void listsBindFromIndexedKeysAndFromOneCommaSeparatedValue() {
        runner.withPropertyValues("client.hosts[0]=a", "client.hosts[1]=b")
                .run(context -> assertThat(context.getBean(ClientProperties.class).hosts()).containsExactly("a", "b"));

        runner.withPropertyValues("client.hosts=a,b")
                .run(context -> assertThat(context.getBean(ClientProperties.class).hosts()).containsExactly("a", "b"));
    }

    @Test
    void mapKeysKeepTheirOriginalSpelling() {
        // The exception to relaxed binding: a map key is data, not a property name, so it is not
        // normalised. client.headers.X-Trace-Id stays X-Trace-Id.
        runner.withPropertyValues("client.headers.X-Trace-Id=abc")
                .run(context -> assertThat(context.getBean(ClientProperties.class).headers())
                        .containsEntry("X-Trace-Id", "abc"));
    }

    @Test
    void aClassWithSettersAndANoArgConstructorStillUsesSetterBinding() {
        runner.withPropertyValues("legacy.name=set-by-setter")
                .run(context -> assertThat(context.getBean(LegacyProperties.class).getName())
                        .isEqualTo("set-by-setter"));
    }

    @Test
    void beanMethodConfigurationPropertiesBindsOntoAnAlreadyConstructedObject() {
        // @ConfigurationProperties on an @Bean method is always setter binding: the object exists
        // before the binder ever sees it. This is how you bind a third-party class you cannot
        // annotate.
        runner.withPropertyValues("third-party.name=bound-later")
                .run(context -> assertThat(context.getBean(ThirdPartyThing.class).getName())
                        .isEqualTo("bound-later"));
    }

    // ---------------------------------------------------------------------------------------

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({ClientProperties.class, LegacyProperties.class})
    static class Config {

        @Bean
        @ConfigurationProperties(prefix = "third-party")
        ThirdPartyThing thirdPartyThing() {
            return new ThirdPartyThing();
        }
    }

    @ConfigurationProperties(prefix = "client")
    record ClientProperties(
            String url,
            @DefaultValue("2") int retries,
            Proxy proxy,
            @DefaultValue Timeouts timeouts,
            @DefaultValue List<String> hosts,
            @DefaultValue Map<String, String> headers) {

        record Proxy(String host, int port) {
        }

        record Timeouts(@DefaultValue("1000") int connect, @DefaultValue("2000") int read) {
        }
    }

    @ConfigurationProperties(prefix = "legacy")
    static class LegacyProperties {

        private String name;

        public String getName() {
            return this.name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    /** Stands in for a class from a library: no Spring annotations, bound from an @Bean method. */
    static class ThirdPartyThing {

        private String name;

        public String getName() {
            return this.name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }
}
