# Testing Spring, end to end

Verified against **Spring Framework 7.0.9 / Spring Boot 4.1.1**. Every claim below is pinned by a
test in [`labs/lab-testing`](../labs/lab-testing); if a claim and a test ever disagree, the test is
right.

This note is not about how to write assertions. It is about the two mechanisms that decide whether
a Spring test suite takes forty seconds or eleven minutes, and whether the tests are testing what
you think they are.

---

## How to work through this note

1. **Read "Before this note".** The JUnit 5 extension model in one paragraph, and why the context
   cache exists at all.
2. **Run `ContextCachingTest` and read it.**
   ```bash
   ./mvnw -pl labs/lab-testing -am test -Dtest=ContextCachingTest
   ```
   It demonstrates the cache and, more usefully, what silently evicts from it.
3. **Read "The context cache"** and study the key. Most slow test suites are explained by that list.
4. **Read "Slices" and "Bean overrides."**
5. **Run and read `TransactionalTestTest`**, then the transactional-test section. The rollback
   behaviour hides a whole class of bug.
6. **Finish with "What this changes for you."**

---

## What you will be able to answer afterwards

- Why does my test suite start the application context twenty times?
- What exactly goes into the context cache key?
- What does `@DirtiesContext` actually cost?
- Why does a `@Transactional` test pass when the same code fails in production?

---

## Before this note

**Read [bean lifecycle and DI](bean-lifecycle.md) and [transactions](transactions.md) first.** This
note is about the container being started repeatedly and about transactions that are deliberately
rolled back, so both are assumed.

**The Java and JUnit you need:**

*JUnit 5 runs on extensions.* `SpringExtension` hooks the lifecycle callbacks JUnit exposes — before
all, before each, after each — and that is the entire integration. There is no test runner magic;
Spring is a guest in JUnit's lifecycle.

*Static state outlives a test class.* JUnit creates a new test **instance** per test method by
default, but statics belong to the class and the classloader, and they persist for the whole JVM.
The context cache is a static map, which is precisely why contexts survive between test classes —
and why a test that mutates global state can affect a class that runs much later.

*Starting a Spring context is expensive, and the cost is per distinct configuration.* Classpath
scanning, bean creation, sometimes a connection pool and an embedded database. Caching is the only
reason an integration suite finishes in minutes rather than hours, so anything that makes two test
classes look different to the cache is quietly buying another full startup.

---

## The context cache

`TestContext` keeps a static cache of application contexts, keyed by the merged configuration:
the configuration classes and locations, active profiles, property sources, context initializers,
bean overrides, context customizers, and the parent context. Two test classes that produce the same
key get **the same context object** — one refresh, one connection pool, one set of singletons.

Which means the cost of a test suite is not the number of test classes. It is **the number of
distinct configurations**.

```java
@SpringBootTest                                            // one context, shared by dozens of classes
@SpringBootTest(properties = "feature.x.enabled=true")     // a second one
@SpringBootTest @MockitoBean UserClient userClient         // a third
@SpringBootTest @ActiveProfiles("integration")             // a fourth
```

Four configurations, four Spring startups, however many test classes there are. The practical
advice follows directly:

- **Standardise on one or two configurations** and let almost every class share them.
- **Push a property into the shared `application-test.yaml`** rather than adding
  `properties = ...` to one class, when the value is harmless everywhere.
- **Group the tests that genuinely need a different configuration**, so the cost is paid once
  rather than per class.
- **Use `@DirtiesContext` only when you have actually corrupted the context**, and prefer
  `AFTER_CLASS` to `AFTER_EACH_TEST_METHOD`. It evicts, and the next class with that configuration
  starts Spring again.

`logging.level.org.springframework.test.context.cache=DEBUG` prints the cache's hit and miss counts
as the suite runs. It is worth turning on once, on your real suite, before deciding anything else
about test speed.

→ `ContextCachingTest`

---

## Slices, and when the whole thing is the right answer

| | Loads | Use it when |
|---|---|---|
| plain JUnit + constructor injection | nothing | the class has no Spring in it — most of your logic |
| `@WebMvcTest` | MVC infrastructure and one controller | testing mapping, binding, status codes, error handling |
| `@DataJdbcTest` / `@DataJpaTest` | the data layer and an embedded database | testing queries and mapping |
| `@SpringBootTest` | everything | wiring, configuration, and the paths that cross layers |

A slice is not a smaller `@SpringBootTest` — it is a different configuration, so it is **another
cache entry**. A suite with one class of each slice type has three contexts and gains nothing;
a suite with fifty controller tests sharing one `@WebMvcTest` configuration gains a great deal.

The class that needs no Spring context at all is still the fastest test you can write, and most
service classes are that class once their dependencies arrive through the constructor. See
[bean lifecycle](bean-lifecycle.md) for why constructor injection is what makes that possible.

---

## Bean overrides

`@MockBean` and `@SpyBean` are gone in Boot 3.4 and later. The replacements live in Spring
Framework itself:

```java
@MockitoBean     UserClient userClient;      // replace the bean with a Mockito mock
@MockitoSpyBean  AuditService auditService;  // wrap the real bean in a spy
@TestBean        Clock clock;                // replace it with a value from a static factory method
```

`@TestBean` is the one to reach for when you do not need a mock at all — a fixed `Clock`, a stub
implementation — because it makes the test read as configuration rather than as behaviour.

All of them are part of the context cache key. A mock that only one test class needs costs that
class its own context.

→ `ContextCachingTest`

---

## Transactional tests

`@Transactional` on a test class means the **test method** runs in a transaction, and that
transaction is **rolled back** when the method finishes. No teardown, no leftover rows, no ordering
coupling between tests. It is an excellent default and it changes two things.

**Anything waiting for a commit never happens.** An `@TransactionalEventListener` in the default
`AFTER_COMMIT` phase does not fire, so a test written to prove the listener works passes without
ever calling it:

```java
@Test
void placingAnOrderSendsTheConfirmation() {
    orders.place(order);
    verify(mailer).send(any());   // never ran, and this fails for the right reason
}                                  // ... or worse, the assertion is on something else and it passes
```

The ways out, in order of preference: test the listener directly rather than through the publisher;
use `@Commit` on that one method; or drive the boundary yourself with
`TestTransaction.flagForCommit()` and `TestTransaction.end()`.

**The test and the code under test share one transaction.** So `REQUIRES_NEW` inside the code under
test really does suspend the test's transaction and commit independently — which means it survives
the rollback and leaves rows behind. See [transactions](transactions.md).

→ `TransactionalTestTest`

---

## What this changes for you

Now that the mechanism is in place, the short version — the things that are true and
surprise people:

| Assumption | Reality |
|---|---|
| Every test class starts its own `ApplicationContext` | Contexts are **cached and shared** by configuration. Identical configuration, one startup |
| Adding a property to one test class is free | It is part of the cache key, so it buys a whole second context |
| `@MockitoBean` just swaps a bean | It is part of the cache key too. Every distinct set of overrides is another context |
| `@DirtiesContext` cleans up after a messy test | It **evicts the cached context**, so the next class with that configuration pays full price |
| A `@Transactional` test behaves like production | It runs in a transaction that is **rolled back**, so nothing that waits for a commit ever happens |

---

## Review checklist

- [ ] How many distinct context configurations does this suite have? Could two of them be one?
- [ ] Is `properties = ...` or `@ActiveProfiles` on a class where the shared test configuration
      would do?
- [ ] Is `@DirtiesContext` there because something is genuinely corrupted, or as a precaution?
- [ ] Does any test rely on a commit that a `@Transactional` test never performs?
- [ ] Could this test be a plain JUnit test with a constructor call instead?
- [ ] Are `@MockitoBean`s replacing things a real in-memory implementation would cover better?
- [ ] Does the suite log context cache hits and misses, so a regression in startup count is visible?

---

## Source map

| Class | Role |
|---|---|
| `test.context.cache.DefaultContextCache` | the cache itself, and the statistics it logs |
| `test.context.MergedContextConfiguration` | the cache key — read `equals` to see exactly what counts |
| `test.context.TestContextManager` | the hooks that drive everything else |
| `test.context.support.DirtiesContextTestExecutionListener` | eviction |
| `test.context.transaction.TransactionalTestExecutionListener` | begin, and roll back unless `@Commit` |
| `test.context.transaction.TestTransaction` | manual control of the boundary inside a test |
| `test.context.bean.override.mockito.MockitoBean` | the replacement for `@MockBean` |
