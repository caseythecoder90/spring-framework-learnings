# Caching, end to end

Verified against **Spring Framework 7.0.9 / Spring Boot 4.1.1**. Every claim below is pinned by a
test in [`labs/lab-caching`](../labs/lab-caching); if a claim and a test ever disagree, the test is
right.

Last note in the Execution track, and the fourth feature in this repo built as an interceptor on a
proxy. By now the shape should be predictable: an annotation, an `@Import`, an operation source, an
interceptor.

---

## How to work through this note

1. **Read "Before this note".** Short — mostly `equals`/`hashCode`, which is what a cache key is.
2. **Run `CacheKeyTest` and read it.**
   ```bash
   ./mvnw -pl labs/lab-caching -am test -Dtest=CacheKeyTest
   ```
   Read the second test carefully. It is a silent data leak, and it is nine lines of Spring source
   away.
3. **Read "The key does not include the method".**
4. **Run and read `CacheSemanticsTest`**, then the sections on the three annotations, `condition`
   versus `unless`, and cached nulls.
5. **Read "The two ways caching quietly does nothing."**
6. **Finish with "What this changes for you."**

---

## What you will be able to answer afterwards

- What exactly is my cache key, and could two different methods produce the same one?
- When does `condition` run compared with `unless`, and why does only one of them save the call?
- Is a `null` result cached, or looked up again next time?
- Why is my cache always cold, with no error anywhere?

---

## Before this note

**Read [the proxy model](proxies.md) first.** Caching is an interceptor, and two of the three ways
it silently does nothing come straight from that note.

**The Java you need:**

*A cache is a map, so a cache key is an `equals`/`hashCode` contract.* Two keys that are `equals`
and share a `hashCode` are the same entry. Spring's default key for a single-argument method is
**the argument itself**, so the argument's own `equals` decides what collides. For a `String` or a
`Long` that is exactly what you want; for a mutable object with identity equality it means you will
never get a hit.

*Records give you `equals` and `hashCode` for free.* That makes a record an excellent composite
cache key and a poor one if any component is mutable — mutate a field after storing and the entry
becomes unreachable, because its hash no longer matches.

*`ConcurrentHashMap` has no expiry.* Spring's simple cache manager is backed by one, so nothing ever
leaves it. There is no TTL and no size bound; it grows until the heap runs out. Every real cache
implementation — Caffeine, Redis — adds that back, which is why the default is for tests and
demonstrations rather than production.

---

## The key does not include the method

`SimpleKeyGenerator.generateKey` is nine lines:

```java
if (params.length == 0)  return SimpleKey.EMPTY;
if (params.length == 1 && param != null && !param.getClass().isArray())  return param;
return new SimpleKey(params);
```

Nowhere does it look at which method it was called for. So:

```java
@Cacheable("shared") String nameFor(String userId)  { ... }
@Cacheable("shared") String emailFor(String userId) { ... }
```

`nameFor("u1")` populates key `"u1"` in cache `shared`. `emailFor("u1")` then **hits that entry**
and returns the name. The second method is never invoked, nothing is logged, and the bug looks like
a data-mapping error somewhere else entirely. When the two methods return different users' data
under the same id, it is a data leak.

Two no-argument methods in the same cache are worse: both use `SimpleKey.EMPTY`, so they always
collide.

Three ways out, in order of preference:

1. **A cache name per method or per entity.** Cheapest and most obvious at a glance.
2. **An explicit SpEL key**: `@Cacheable(cacheNames = "shared", key = "'name:' + #userId")`.
3. A custom `KeyGenerator` that includes the method — global, and easy to forget you did it.

→ `CacheKeyTest`

---

## The three annotations

| Annotation | Runs the method? | Writes the cache? |
|---|---|---|
| `@Cacheable` | only on a miss | on a miss |
| `@CachePut` | **always** | always |
| `@CacheEvict` | always | removes |

`@CacheEvict(allEntries = true)` clears the whole cache rather than one key, and
`beforeInvocation = true` evicts even if the method then throws — worth setting when the eviction
matters more than the method succeeding.

---

## `condition` versus `unless`

```java
@Cacheable(cacheNames = "items", condition = "!#id.startsWith('tmp')")   // before: sees arguments
@Cacheable(cacheNames = "items", unless = "#result.isEmpty()")           // after: sees #result
```

`condition` is evaluated **before** the lookup, so a false condition skips caching entirely and the
method just runs. `unless` is evaluated **after** the method has already run, so it can inspect
`#result` but cannot save you the call. An `unless` that filters out empty results means those
results are recomputed every single time.

---

## `null` is a cached value

`ConcurrentMapCacheManager` allows null values by default, so a lookup that finds nothing stores a
null and the next call is a hit. Usually what you want — it stops a missing id hammering the
database. Occasionally a surprise, when the thing does appear later and the cache keeps saying no.

Redis and Caffeine behave differently here, so check before assuming.

---

## The two ways caching quietly does nothing

**Self-invocation.** An internal call skips the proxy, so it skips the cache. Fourth appearance in
this repo; see [the proxy model](proxies.md).

**A non-public method.** `AnnotationCacheOperationSource` defaults to `publicMethodsOnly = true`, so
`@Cacheable` on a package-private or protected method is not an error. It is simply never applied.

Both fail silently, and both look identical from outside: the cache is just always cold.

→ `CacheSemanticsTest`

---

## What this changes for you

Now that the mechanism is in place, the short version — the things that are true and
surprise people:

| Assumption | Reality |
|---|---|
| The cache key identifies the method | It does **not**. It is the arguments, and only the arguments |
| Two methods in one cache are independent | They **share entries** if the arguments match |
| A `null` result means a cache miss next time | `null` is cached like any other value |
| `condition` and `unless` are two spellings of one thing | `condition` runs **before** the method, `unless` **after** |
| A private `@Cacheable` method still caches | It is silently ignored |

---

## Review checklist

- [ ] Does any cache name hold entries from more than one method?
- [ ] Any no-argument `@Cacheable` sharing a cache with anything else?
- [ ] Is the key explicit where the arguments alone do not identify the entry?
- [ ] Is the method public, and called from outside the bean?
- [ ] Is a cached `null` correct here, or should misses stay uncached?
- [ ] `unless` filtering results that are then recomputed on every call?
- [ ] Is there a TTL? `ConcurrentMapCacheManager` has none, and grows without bound.
- [ ] Are cache keys serialisable, if the cache is not in-process?

---

## Reading the source yourself

Everything named below is already on your machine, but as compiled jars. Unpack the sources once —
they land in `.spring-sources/`, which is gitignored:

```bash
./tools/fetch-spring-sources.sh spring-context
```

Now you can grep them, which is faster than any IDE search:

```bash
grep -rn "class ProxyCachingConfiguration" .spring-sources/
```

**Then walk the path below, in order.** It is not a list of classes to read in full. Each stop names
one method and one thing to notice, and that is all you need from it — most of these classes are
hundreds of lines you can safely ignore.

Do it once with a debugger rather than by reading. Set a breakpoint where the path says to start,
run the lab test for this note, and step through. One pass is worth more than an hour of reading,
because you see the real values.

New to this? [How to read Spring source](reading-the-source.md) is the general method — how to find
the entry point for any feature, and the five shapes it will turn out to be.

<!-- widget:path:cache-invocation -->

**You have understood this when you can say, without looking:** why two `@Cacheable` methods can
return each other's data.

---

## The classes involved

For reference later. The ordered walk is above; this is the same material as a lookup table.

| Class | Role |
|---|---|
| `cache.annotation.ProxyCachingConfiguration` | what `@EnableCaching` imports |
| `cache.annotation.AnnotationCacheOperationSource` | annotations to `CacheOperation`, and `publicMethodsOnly` |
| `cache.interceptor.CacheInterceptor` | the thin adapter |
| `cache.interceptor.CacheAspectSupport` | the real logic: lookup, invoke, put, evict |
| `cache.interceptor.SimpleKeyGenerator` | nine lines, and the collision |
| `cache.concurrent.ConcurrentMapCacheManager` | the default: no TTL, nulls allowed |
