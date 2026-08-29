package com.caseythecoder.spring.caching;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three annotations, the two ways to say "not this one", and the two ways caching quietly does
 * nothing at all.
 *
 * <p>Notes: docs/caching.md.
 */
@SpringJUnitConfig(CacheSemanticsTest.Config.class)
class CacheSemanticsTest {

    @Autowired
    Store store;

    @Autowired
    Counters counters;

    @Autowired
    CacheManager cacheManager;

    @BeforeEach
    void reset() {
        counters.loads.set(0);
        cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
    }

    @Test
    void theSecondCallDoesNotReachTheMethod() {
        assertThat(store.load("a")).isEqualTo("value-a-1");
        assertThat(store.load("a")).isEqualTo("value-a-1");

        assertThat(counters.loads.get()).isEqualTo(1);
    }

    @Test
    void cachePutAlwaysRunsAndRefreshesTheEntry() {
        store.load("a");
        assertThat(counters.loads.get()).isEqualTo(1);

        assertThat(store.refresh("a")).isEqualTo("value-a-2");
        assertThat(counters.loads.get()).as("@CachePut never skips the method").isEqualTo(2);

        assertThat(store.load("a"))
                .as("and the refreshed value is what the cache now holds")
                .isEqualTo("value-a-2");
        assertThat(counters.loads.get()).isEqualTo(2);
    }

    @Test
    void cacheEvictRemovesOneEntry() {
        store.load("a");
        store.load("b");
        assertThat(counters.loads.get()).isEqualTo(2);

        store.evict("a");

        store.load("a");
        store.load("b");
        assertThat(counters.loads.get()).as("only a was reloaded").isEqualTo(3);
    }

    @Test
    void allEntriesClearsTheWholeCache() {
        store.load("a");
        store.load("b");
        store.evictAll();

        store.load("a");
        store.load("b");
        assertThat(counters.loads.get()).isEqualTo(4);
    }

    @Test
    void conditionIsCheckedBeforeTheMethodRuns() {
        // condition sees the arguments only, so it can skip the cache lookup entirely.
        store.conditional("skip-me");
        store.conditional("skip-me");

        assertThat(counters.loads.get()).as("never cached, so both calls ran").isEqualTo(2);
    }

    @Test
    void unlessIsCheckedAfterAndSeesTheResult() {
        // unless sees the return value, which means the method has already run.
        assertThat(store.notEmpty("empty")).isEmpty();
        assertThat(store.notEmpty("empty")).isEmpty();

        assertThat(counters.loads.get())
                .as("an empty result is never stored, so it is recomputed every time")
                .isEqualTo(2);
    }

    @Test
    void nullIsCachedLikeAnyOtherValue() {
        // ConcurrentMapCacheManager allows null values by default, so a miss in your data source
        // becomes a cached null rather than a repeated lookup. Usually what you want, occasionally
        // a surprise.
        assertThat(store.maybeNull("nothing")).isNull();
        assertThat(store.maybeNull("nothing")).isNull();

        assertThat(counters.loads.get()).isEqualTo(1);
        assertThat(cacheManager.getCache("items").get("nothing")).isNotNull();
    }

    @Test
    void anInternalCallIsNotCached() {
        // The proxy, for the fourth time in this repo.
        store.loadTwiceInternally("a");

        assertThat(counters.loads.get())
                .as("both internal calls ran; neither went through the proxy")
                .isEqualTo(2);
    }

    static class Counters {

        final AtomicInteger loads = new AtomicInteger();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableCaching
    static class Config {

        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager("items");
        }

        @Bean
        Counters counters() {
            return new Counters();
        }

        @Bean
        Store store(Counters counters) {
            return new Store(counters);
        }
    }

    static class Store {

        private final Counters counters;

        Store(Counters counters) {
            this.counters = counters;
        }

        @Cacheable("items")
        public String load(String id) {
            return "value-" + id + "-" + counters.loads.incrementAndGet();
        }

        @CachePut(cacheNames = "items", key = "#id")
        public String refresh(String id) {
            return "value-" + id + "-" + counters.loads.incrementAndGet();
        }

        @CacheEvict(cacheNames = "items", key = "#id")
        public void evict(String id) {
        }

        @CacheEvict(cacheNames = "items", allEntries = true)
        public void evictAll() {
        }

        @Cacheable(cacheNames = "items", condition = "!#id.startsWith('skip')")
        public String conditional(String id) {
            return "value-" + id + "-" + counters.loads.incrementAndGet();
        }

        @Cacheable(cacheNames = "items", unless = "#result.isEmpty()")
        public String notEmpty(String id) {
            counters.loads.incrementAndGet();
            return "";
        }

        @Cacheable("items")
        public String maybeNull(String id) {
            counters.loads.incrementAndGet();
            return null;
        }

        public void loadTwiceInternally(String id) {
            load(id);
            load(id);
        }
    }
}
