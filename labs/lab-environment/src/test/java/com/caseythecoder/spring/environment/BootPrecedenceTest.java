package com.caseythecoder.spring.environment;

import org.junit.jupiter.api.Test;

import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boot's precedence table, from the top and from the bottom, using real
 * {@code application.yaml} and {@code application-staging.yaml} files in
 * {@code src/test/resources}.
 *
 * <p>The claim worth proving is the third one. A profile-specific file is <em>additive</em>: it
 * overrides the keys it mentions and everything else still comes from the base file. People
 * routinely copy the whole base file into {@code application-prod.yaml} to be safe, and then have
 * two files to keep in step forever.
 *
 * <p>Notes: docs/environment.md, "Boot's order".
 */
class BootPrecedenceTest {

    @Test
    void commandLineArgumentsBeatEverythingElse() {
        withApplication(environment ->
                        assertThat(environment.getProperty("demo.value")).isEqualTo("from-command-line"),
                builder -> builder.properties("demo.value=from-default-properties").profiles("staging"),
                "--demo.value=from-command-line");
    }

    @Test
    void aProfileSpecificFileBeatsTheBaseFile() {
        withApplication(environment ->
                        assertThat(environment.getProperty("demo.value")).isEqualTo("from-application-staging"),
                builder -> builder.properties("demo.value=from-default-properties").profiles("staging"));
    }

    @Test
    void profileSpecificFilesAreAdditiveNotReplacements() {
        withApplication(environment -> assertThat(environment.getProperty("demo.only-in-base"))
                        .as("application.yaml is still read while a profile is active")
                        .isEqualTo("base"),
                builder -> builder.profiles("staging"));
    }

    @Test
    void theBaseFileBeatsDefaultProperties() {
        withApplication(environment ->
                        assertThat(environment.getProperty("demo.value")).isEqualTo("from-application"),
                builder -> builder.properties("demo.value=from-default-properties"));
    }

    @Test
    void defaultPropertiesAreTheLastResortAndStillUsefulForOne() {
        withApplication(environment ->
                        assertThat(environment.getProperty("demo.unset-anywhere-else")).isEqualTo("fallback"),
                builder -> builder.properties("demo.unset-anywhere-else=fallback"));
    }

    @Test
    void anActiveProfileIsVisibleOnTheEnvironmentItself() {
        withApplication(environment -> assertThat(environment.getActiveProfiles()).containsExactly("staging"),
                builder -> builder.profiles("staging"));
    }

    // ---------------------------------------------------------------------------------------

    private static void withApplication(java.util.function.Consumer<Environment> assertions,
            java.util.function.UnaryOperator<SpringApplicationBuilder> customiser, String... args) {

        SpringApplicationBuilder builder = customiser.apply(new SpringApplicationBuilder(Config.class)
                .web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF));

        try (ConfigurableApplicationContext context = builder.run(args)) {
            assertions.accept(context.getEnvironment());
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Config {
    }
}
