package com.caseythecoder.spring.testing;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestClassOrder;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The single biggest lever on how long a test suite takes, and the one most people never touch:
 * Spring caches application contexts across test classes, keyed by the configuration that produced
 * them.
 *
 * <p>Two test classes with identical configuration share one context and one startup. Change
 * anything the key is built from - a property, an active profile, a bean override, a
 * {@code @DirtiesContext} - and you pay for a whole new context. A suite with fifteen slightly
 * different configurations starts Spring fifteen times.
 *
 * <p>Each nested class below records the identity of the context it was given; the assertions at
 * the end compare them.
 *
 * <p>Notes: docs/testing.md, "The context cache".
 */
@TestClassOrder(ClassOrderer.OrderAnnotation.class)
class ContextCachingTest {

    /** Identity hash of the context each nested class was given, by name. */
    static final Map<String, Integer> contexts = new LinkedHashMap<>();

    @AfterAll
    static void theCacheKeyIsTheConfigurationNotTheTestClass() {
        assertThat(contexts)
                .containsKeys("first", "second", "withProperty", "withMock", "dirtied", "afterDirtied");

        assertThat(contexts.get("first"))
                .as("two test classes, identical configuration, one context and one startup")
                .isEqualTo(contexts.get("second"));

        assertThat(contexts.get("withProperty"))
                .as("@TestPropertySource is part of the cache key, so this is a second context")
                .isNotEqualTo(contexts.get("first"));

        assertThat(contexts.get("withMock"))
                .as("a bean override is part of the key too - @MockitoBean is not free")
                .isNotEqualTo(contexts.get("first"));

        assertThat(contexts.get("dirtied"))
                .as("@DirtiesContext evicts afterwards, so this class itself still got the cached context")
                .isEqualTo(contexts.get("first"));

        assertThat(contexts.get("afterDirtied"))
                .as("and the next class with that same configuration pays for a fresh one")
                .isNotEqualTo(contexts.get("first"));
    }

    @Nested
    @Order(1)
    @SpringJUnitConfig(SharedConfig.class)
    class First {

        @Autowired
        ApplicationContext context;

        @Test
        void record() {
            contexts.put("first", System.identityHashCode(context));
        }
    }

    @Nested
    @Order(2)
    @SpringJUnitConfig(SharedConfig.class)
    class Second {

        @Autowired
        ApplicationContext context;

        @Test
        void record() {
            contexts.put("second", System.identityHashCode(context));
        }
    }

    @Nested
    @Order(3)
    @SpringJUnitConfig(SharedConfig.class)
    @TestPropertySource(properties = "anything=at-all")
    class WithProperty {

        @Autowired
        ApplicationContext context;

        @Test
        void record() {
            contexts.put("withProperty", System.identityHashCode(context));
        }
    }

    @Nested
    @Order(4)
    @SpringJUnitConfig(SharedConfig.class)
    class WithMock {

        @MockitoBean
        Collaborator collaborator;

        @Autowired
        ApplicationContext context;

        @Test
        void theMockReplacesTheRealBean() {
            contexts.put("withMock", System.identityHashCode(context));
            assertThat(context.getBean(Collaborator.class)).isSameAs(collaborator);
        }
    }

    /**
     * Marked dirty <em>after</em> the class, so this run reuses the shared context and only the
     * next class needing that configuration pays. The eviction is the point, not the reuse.
     */
    @Nested
    @Order(5)
    @SpringJUnitConfig(SharedConfig.class)
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    class Dirtied {

        @Autowired
        ApplicationContext context;

        @Test
        void record() {
            contexts.put("dirtied", System.identityHashCode(context));
        }
    }

    /** Same configuration as {@code First}, and it will not get that context back. */
    @Nested
    @Order(6)
    @SpringJUnitConfig(SharedConfig.class)
    class AfterDirtied {

        @Autowired
        ApplicationContext context;

        @Test
        void record() {
            contexts.put("afterDirtied", System.identityHashCode(context));
        }
    }

    // ---------------------------------------------------------------------------------------

    static class Collaborator {

        String describe() {
            return "the real one";
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class SharedConfig {

        @Bean
        Collaborator collaborator() {
            return new Collaborator();
        }
    }
}
