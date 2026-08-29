package com.caseythecoder.spring.environment;

import java.util.Map;

import org.junit.jupiter.api.Test;

import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code Environment} is not a map. It is an <em>ordered list</em> of maps, and every lookup
 * walks that list and returns the first hit.
 *
 * <p>Once that is the mental model, the whole of Boot's twenty-odd-entry precedence table stops
 * needing to be memorised: it is just the order the sources were added in.
 *
 * <p>Notes: docs/environment.md, "It is a list, not a map".
 */
class PropertySourceOrderTest {

    @Test
    void theFirstSourceThatHasTheKeyWins() {
        StandardEnvironment environment = new StandardEnvironment();
        MutablePropertySources sources = environment.getPropertySources();

        sources.addFirst(new MapPropertySource("low", Map.of("colour", "blue")));
        sources.addFirst(new MapPropertySource("high", Map.of("colour", "red")));

        assertThat(environment.getProperty("colour")).isEqualTo("red");
    }

    @Test
    void aSourceWithoutTheKeyIsSkippedRatherThanAnsweringNull() {
        // The reason a profile-specific file can override one key and leave the rest alone: not
        // finding a key is not the same as finding nothing.
        StandardEnvironment environment = new StandardEnvironment();
        MutablePropertySources sources = environment.getPropertySources();

        sources.addFirst(new MapPropertySource("base", Map.of("colour", "blue", "size", "large")));
        sources.addFirst(new MapPropertySource("override", Map.of("colour", "red")));

        assertThat(environment.getProperty("colour")).isEqualTo("red");
        assertThat(environment.getProperty("size")).isEqualTo("large");
    }

    @Test
    void anEmptyStringIsAValueAndStopsTheSearch() {
        // A blank value in a deployment's config map is not "unset". It shadows everything below.
        StandardEnvironment environment = new StandardEnvironment();
        MutablePropertySources sources = environment.getPropertySources();

        sources.addFirst(new MapPropertySource("base", Map.of("colour", "blue")));
        sources.addFirst(new MapPropertySource("override", Map.of("colour", "")));

        assertThat(environment.getProperty("colour")).isEmpty();
    }

    @Test
    void addLastPutsASourceUnderneathEverythingAlreadyThere() {
        StandardEnvironment environment = new StandardEnvironment();
        MutablePropertySources sources = environment.getPropertySources();

        sources.addFirst(new MapPropertySource("first", Map.of("colour", "red")));
        sources.addLast(new MapPropertySource("last", Map.of("colour", "green")));

        assertThat(environment.getProperty("colour")).isEqualTo("red");
        assertThat(sources.stream().map(source -> source.getName()))
                .endsWith("last");
    }

    @Test
    void placeholdersAreResolvedOnReadNotOnLoad() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("app", Map.of(
                "app.name", "orders",
                "app.queue", "${app.name}-events")));

        assertThat(environment.getProperty("app.queue")).isEqualTo("orders-events");
    }

    @Test
    void aPlaceholderDefaultCanItselfBeAPlaceholder() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("app", Map.of(
                "fallback", "from-fallback",
                "app.url", "${primary:${fallback}}")));

        assertThat(environment.getProperty("app.url")).isEqualTo("from-fallback");
    }

    @Test
    void anUnresolvablePlaceholderThrowsRatherThanReturningTheRawText() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("app", Map.of(
                "app.url", "${nobody.set.this}")));

        assertThat(catchThrowable(() -> environment.getProperty("app.url")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nobody.set.this");
    }

    private static Throwable catchThrowable(Runnable runnable) {
        try {
            runnable.run();
            return null;
        }
        catch (Throwable ex) {
            return ex;
        }
    }
}
