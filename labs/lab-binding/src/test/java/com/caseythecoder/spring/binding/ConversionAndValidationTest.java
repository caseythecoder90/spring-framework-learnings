package com.caseythecoder.spring.binding;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import org.junit.jupiter.api.Test;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.ConfigurationPropertiesBindException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.boot.convert.DataSizeUnit;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.util.unit.DataSize;
import org.springframework.util.unit.DataUnit;
import org.springframework.validation.annotation.Validated;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the binder converts for free, and how to make a bad configuration fail at startup instead of
 * at 3am.
 *
 * <p>The argument for {@code @Validated} on configuration is the same as the argument for
 * constructor binding: configuration is read once, so the only sensible time to find out it is
 * wrong is before the application starts serving traffic.
 *
 * <p>Notes: docs/property-binding.md, "Conversion" and "Fail at startup".
 */
class ConversionAndValidationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(Config.class);

    @Test
    void durationsAcceptTheSuffixFormAndTheIso8601Form() {
        runner.withPropertyValues("convert.timeout=5s")
                .run(context -> assertThat(context.getBean(ConvertProperties.class).timeout())
                        .isEqualTo(Duration.ofSeconds(5)));

        runner.withPropertyValues("convert.timeout=PT5S")
                .run(context -> assertThat(context.getBean(ConvertProperties.class).timeout())
                        .isEqualTo(Duration.ofSeconds(5)));
    }

    @Test
    void aBareNumberUsesTheUnitFromDurationUnitAndMillisecondsWithoutIt() {
        runner.withPropertyValues("convert.timeout=5", "convert.linger=5")
                .run(context -> {
                    ConvertProperties properties = context.getBean(ConvertProperties.class);
                    assertThat(properties.timeout())
                            .as("@DurationUnit(SECONDS)")
                            .isEqualTo(Duration.ofSeconds(5));
                    assertThat(properties.linger())
                            .as("no @DurationUnit, so the default unit is milliseconds")
                            .isEqualTo(Duration.ofMillis(5));
                });
    }

    @Test
    void dataSizesUnderstandTheUsualSuffixes() {
        runner.withPropertyValues("convert.max-upload=10MB")
                .run(context -> assertThat(context.getBean(ConvertProperties.class).maxUpload())
                        .isEqualTo(DataSize.ofMegabytes(10)));
    }

    @Test
    void enumsAreMatchedLooselyOnCaseAndPunctuation() {
        for (String written : new String[] {"READ_ONLY", "read-only", "readOnly", "read_only"}) {
            runner.withPropertyValues("convert.mode=" + written)
                    .run(context -> assertThat(context.getBean(ConvertProperties.class).mode())
                            .isEqualTo(Mode.READ_ONLY));
        }
    }

    @Test
    void aConverterAnnotatedConfigurationPropertiesBindingIsUsedForYourOwnTypes() {
        runner.withPropertyValues("convert.endpoint=host:8080")
                .run(context -> {
                    Endpoint endpoint = context.getBean(ConvertProperties.class).endpoint();
                    assertThat(endpoint.host()).isEqualTo("host");
                    assertThat(endpoint.port()).isEqualTo(8080);
                });
    }

    @Test
    void aValueThatCannotBeConvertedFailsTheContextAndNamesTheProperty() {
        runner.withPropertyValues("convert.timeout=next tuesday")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasStackTraceContaining("convert.timeout"));
    }

    @Test
    void validatedTurnsABadConfigurationIntoAStartupFailure() {
        new ApplicationContextRunner()
                .withUserConfiguration(ValidatedConfig.class)
                .withPropertyValues("validated.name=", "validated.retries=-1")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .isInstanceOf(ConfigurationPropertiesBindException.class)
                        .hasStackTraceContaining("validated.name")
                        .hasStackTraceContaining("validated.retries"));
    }

    @Test
    void withoutValidatedTheSameConfigurationStartsHappilyAndFailsLater() {
        new ApplicationContextRunner()
                .withUserConfiguration(UnvalidatedConfig.class)
                .withPropertyValues("unvalidated.name=", "unvalidated.retries=-1")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(UnvalidatedProperties.class).retries()).isEqualTo(-1);
                });
    }

    // ---------------------------------------------------------------------------------------

    enum Mode {
        READ_ONLY, READ_WRITE
    }

    record Endpoint(String host, int port) {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ConvertProperties.class)
    static class Config {

        @Bean
        @ConfigurationPropertiesBinding
        Converter<String, Endpoint> endpointConverter() {
            return source -> {
                String[] parts = source.split(":", 2);
                return new Endpoint(parts[0], Integer.parseInt(parts[1]));
            };
        }
    }

    @ConfigurationProperties(prefix = "convert")
    record ConvertProperties(
            @DurationUnit(ChronoUnit.SECONDS) @DefaultValue("30s") Duration timeout,
            @DefaultValue("0") Duration linger,
            @DataSizeUnit(DataUnit.MEGABYTES) @DefaultValue("1MB") DataSize maxUpload,
            @DefaultValue("READ_WRITE") Mode mode,
            @DefaultValue("localhost:80") Endpoint endpoint) {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ValidatedProperties.class)
    static class ValidatedConfig {
    }

    @Validated
    @ConfigurationProperties(prefix = "validated")
    record ValidatedProperties(@NotEmpty String name, @Min(0) int retries) {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(UnvalidatedProperties.class)
    static class UnvalidatedConfig {
    }

    @ConfigurationProperties(prefix = "unvalidated")
    record UnvalidatedProperties(String name, int retries) {
    }
}
