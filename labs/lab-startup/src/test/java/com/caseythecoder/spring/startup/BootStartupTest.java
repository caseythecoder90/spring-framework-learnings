package com.caseythecoder.spring.startup;

import com.caseythecoder.spring.support.Recorder;
import org.junit.jupiter.api.Test;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextRefreshedEvent;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boot's own startup phases, which are the ones people actually reach for, and which sit
 * <em>outside</em> {@code refresh()} entirely: {@code SpringApplication.run} refreshes the context
 * and then keeps going.
 *
 * <p>The practical consequence is the ordering below. A {@code CommandLineRunner} is not part of
 * container startup - by the time it runs, the context has been refreshed, every
 * {@code SmartLifecycle} has started, and in a web application the port is already open.
 *
 * <p>Notes: docs/startup.md, "Where Boot joins in".
 */
class BootStartupTest {

    @Test
    void runnersRunAfterTheContextIsFullyStartedAndBeforeItIsReady() {
        Recorder recorder = new Recorder();
        Config.recorder = recorder;

        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(Config.class)
                .web(WebApplicationType.NONE)
                .bannerMode(org.springframework.boot.Banner.Mode.OFF)
                .run()) {

            assertThat(recorder.labels()).containsSubsequence(
                    // Published at the end of refresh(), by the context itself.
                    "ContextRefreshedEvent",
                    // Everything from here down is SpringApplication, not the container.
                    "ApplicationStartedEvent",
                    "ApplicationRunner",
                    "ApplicationReadyEvent");

            assertThat(recorder.labels()).containsSubsequence(
                    "ApplicationStartedEvent",
                    "CommandLineRunner",
                    "ApplicationReadyEvent");
        }
    }

    @Test
    void aFailingRunnerStopsTheApplicationFromEverBecomingReady() {
        Recorder recorder = new Recorder();
        FailingRunnerConfig.recorder = recorder;

        try {
            new SpringApplicationBuilder(FailingRunnerConfig.class)
                    .web(WebApplicationType.NONE)
                    .bannerMode(org.springframework.boot.Banner.Mode.OFF)
                    // The failure is the point of the test; its stack trace is not.
                    .properties("logging.level.org.springframework.boot.SpringApplication=OFF")
                    .run()
                    .close();
        }
        catch (IllegalStateException expected) {
            // SpringApplication.callRunners lets the exception out and closes the context, so a
            // runner is a legitimate place to fail fast on a bad configuration.
        }

        assertThat(recorder.labels())
                .contains("CommandLineRunner")
                .doesNotContain("ApplicationReadyEvent");
    }

    // ---------------------------------------------------------------------------------------

    @Configuration(proxyBeanMethods = false)
    static class Config {

        static Recorder recorder;

        @Bean
        ApplicationListener<ContextRefreshedEvent> refreshed() {
            return event -> recorder.record("ContextRefreshedEvent");
        }

        @Bean
        ApplicationListener<ApplicationStartedEvent> started() {
            return event -> recorder.record("ApplicationStartedEvent");
        }

        @Bean
        ApplicationListener<ApplicationReadyEvent> ready() {
            return event -> recorder.record("ApplicationReadyEvent");
        }

        @Bean
        ApplicationRunner applicationRunner() {
            return (ApplicationArguments args) -> recorder.record("ApplicationRunner");
        }

        @Bean
        CommandLineRunner commandLineRunner() {
            return args -> recorder.record("CommandLineRunner");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class FailingRunnerConfig {

        static Recorder recorder;

        @Bean
        ApplicationListener<ApplicationReadyEvent> ready() {
            return event -> recorder.record("ApplicationReadyEvent");
        }

        @Bean
        CommandLineRunner failing() {
            return args -> {
                recorder.record("CommandLineRunner");
                throw new IllegalStateException("bad configuration, refusing to serve traffic");
            };
        }
    }
}
