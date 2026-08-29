package com.caseythecoder.spring.caching;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.cache.interceptor.SimpleKey;
import org.springframework.cache.interceptor.SimpleKeyGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The default key is built from the method <em>arguments</em> and nothing else.
 * {@code SimpleKeyGenerator.generateKey} never looks at which method it was called for.
 *
 * <p>So two {@code @Cacheable} methods sharing a cache name and an argument type share entries, and
 * one returns the other's data. It is a real bug, it is silent, and it is a data leak when the two
 * methods return different things for the same id.
 *
 * <p>Notes: docs/caching.md, "The key does not include the method".
 */
@SpringJUnitConfig(CacheKeyTest.Config.class)
class CacheKeyTest {

    @Autowired
    Lookups lookups;

    @Autowired
    Counters counters;

    @Autowired
    CacheManager cacheManager;

    @BeforeEach
    void reset() {
        counters.name.set(0);
        counters.email.set(0);
        counters.noArgs.set(0);
        cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
    }

    @Test
    void theGeneratedKeyIsJustTheArgument() {
        assertThat(SimpleKeyGenerator.generateKey("u1")).isEqualTo("u1");
        assertThat(SimpleKeyGenerator.generateKey()).isEqualTo(SimpleKey.EMPTY);
        assertThat(SimpleKeyGenerator.generateKey("a", "b")).isEqualTo(new SimpleKey("a", "b"));
    }

    @Test
    void twoMethodsSharingACacheAndAnArgumentTypeCollide() {
        assertThat(lookups.nameFor("u1")).isEqualTo("name-for-u1");

        // Different method, same cache, same argument. It hits the entry the other one wrote.
        assertThat(lookups.emailFor("u1"))
                .as("this should be email-for-u1, and it is not")
                .isEqualTo("name-for-u1");

        assertThat(counters.email.get())
                .as("emailFor was never actually invoked; it read nameFor's entry")
                .isZero();
    }

    @Test
    void separateCacheNamesFixTheCollision() {
        assertThat(lookups.nameInOwnCache("u1")).isEqualTo("name-for-u1");
        assertThat(lookups.emailInOwnCache("u1")).isEqualTo("email-for-u1");
    }

    @Test
    void everyNoArgumentMethodInACacheSharesOneKey() {
        // SimpleKey.EMPTY. Two no-arg methods in the same cache always collide.
        lookups.everything();
        lookups.everything();

        assertThat(counters.noArgs.get()).isEqualTo(1);
        assertThat(cacheManager.getCache("shared").get(SimpleKey.EMPTY)).isNotNull();
    }

    @Test
    void aSpelKeyIsTheUsualFix() {
        assertThat(lookups.nameWithExplicitKey("u1")).isEqualTo("name-for-u1");
        assertThat(cacheManager.getCache("shared").get("name:u1")).isNotNull();
    }

    static class Counters {

        final AtomicInteger name = new AtomicInteger();

        final AtomicInteger email = new AtomicInteger();

        final AtomicInteger noArgs = new AtomicInteger();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableCaching
    static class Config {

        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager("shared", "names", "emails");
        }

        @Bean
        Counters counters() {
            return new Counters();
        }

        @Bean
        Lookups lookups(Counters counters) {
            return new Lookups(counters);
        }
    }

    static class Lookups {

        private final Counters counters;

        Lookups(Counters counters) {
            this.counters = counters;
        }

        @Cacheable("shared")
        public String nameFor(String userId) {
            counters.name.incrementAndGet();
            return "name-for-" + userId;
        }

        @Cacheable("shared")
        public String emailFor(String userId) {
            counters.email.incrementAndGet();
            return "email-for-" + userId;
        }

        @Cacheable("names")
        public String nameInOwnCache(String userId) {
            return "name-for-" + userId;
        }

        @Cacheable("emails")
        public String emailInOwnCache(String userId) {
            return "email-for-" + userId;
        }

        @Cacheable("shared")
        public String everything() {
            counters.noArgs.incrementAndGet();
            return "all";
        }

        @Cacheable(cacheNames = "shared", key = "'name:' + #userId")
        public String nameWithExplicitKey(String userId) {
            return "name-for-" + userId;
        }
    }
}
