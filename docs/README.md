# Notes

One note per Spring feature. Each one is paired with a lab module whose tests prove what the note
claims, so the notes cannot quietly rot: `mvn test` fails when Spring stops behaving the way the
note says it does.

| Note | Lab | Covers |
|---|---|---|
| **Foundations** | | |
| [annotations.md](annotations.md) | [`labs/lab-annotations`](../labs/lab-annotations) | `MergedAnnotations`, meta-annotations, `@AliasFor`, search strategies |
| [proxies.md](proxies.md) | [`labs/lab-proxies`](../labs/lab-proxies) | JDK vs CGLIB, self-invocation, auto-proxy creation, advice ordering |
| [bean-lifecycle.md](bean-lifecycle.md) | [`labs/lab-lifecycle`](../labs/lab-lifecycle) | callback order, injection styles, circular references, scopes |
| [startup.md](startup.md) | [`labs/lab-startup`](../labs/lab-startup) | `refresh()` in order, `SmartLifecycle` phases, the bean that misses every `BeanPostProcessor` |
| **Configuration** | | |
| [property-binding.md](property-binding.md) | [`labs/lab-binding`](../labs/lab-binding) | relaxed binding, constructor binding, conversion, `@Validated` |
| [environment.md](environment.md) | [`labs/lab-environment`](../labs/lab-environment) | `PropertySource` precedence, profile expressions, placeholders |
| [conditions.md](conditions.md) | [`labs/lab-conditions`](../labs/lab-conditions) | `@Conditional`, `@ConditionalOnMissingBean`, auto-configuration ordering |
| **Web** | | |
| [web-mvc.md](web-mvc.md) | [`labs/lab-web`](../labs/lab-web) | `DispatcherServlet`, argument resolution, message converters, `@ExceptionHandler` |
| **Data** | | |
| [transactions.md](transactions.md) | [`labs/lab-transactions`](../labs/lab-transactions) | propagation, rollback rules, the rollback-only trap, `readOnly` |
| **Execution** | | |
| [scheduling.md](scheduling.md) | [`labs/lab-scheduling`](../labs/lab-scheduling) | `@EnableScheduling`, the pool of one, `fixedRate` vs `fixedDelay`, error suppression |
| [events.md](events.md) | [`labs/lab-events`](../labs/lab-events) | `ApplicationEventPublisher`, `@EventListener`, `@Async`, `@TransactionalEventListener` |
| [async.md](async.md) | [`labs/lab-async`](../labs/lab-async) | executor resolution, queue-before-pool, what `@Async` gives up |
| [retry.md](retry.md) | [`labs/lab-retry`](../labs/lab-retry) | native `@Retryable`, backoff and jitter, `@ConcurrencyLimit` |
| [caching.md](caching.md) | [`labs/lab-caching`](../labs/lab-caching) | key generation and collisions, `condition` vs `unless`, the silent no-ops |
| **Testing** | | |
| [testing.md](testing.md) | [`labs/lab-testing`](../labs/lab-testing) | the context cache and its key, bean overrides, transactional tests |

Plus [reading-the-source.md](reading-the-source.md), which is method rather than feature.

Version under study: **Spring Framework 7.0.9 / Spring Boot 4.1.1**, pinned in the root `pom.xml`.

---

## Where to start

The tracks are ordered, and the order matters. **Foundations first** — three of the four notes in
every other track come back to the proxy model or the annotation model, and reading those two first
turns most of the rest into "oh, that again".

After Foundations, take whichever track matches what you are working on. Configuration and Data are
the two that pay off fastest in a code review.

---

## How a note gets written

Two things matter: how the material is **found**, and how the note is **shaped**. They are different
problems and the second one took a rewrite to get right.

### Finding the material

The order matters. Writing the prose first produces confident, plausible, wrong notes.

**1. Read the actual source.**

```bash
./tools/fetch-spring-sources.sh
grep -rn "class TaskSchedulerRouter" .spring-sources/
```

Not the reference docs, not a blog post — the implementation. The reference docs describe intent;
the source describes behaviour, and the gap between the two is where the interesting notes live.

**2. Write a test that proves one claim.** Preferably one that would fail if the claim were wrong in
either direction. Prefer latches and recorded thread names over `Thread.sleep` and hope; where
timing genuinely is the subject, assert lower bounds and differences rather than exact figures.

**3. Only then write the prose,** with a `→ TestName` pointer under each section.

**4. Record what surprised you.** A note that only restates the reference documentation is not worth
keeping. The parts worth writing down are the ones you had wrong beforehand.

### Shaping the note

"Record what surprised you" produces a good reference and a **bad first read**. A surprises table
only lands if the reader already had the assumption; opening with one leaves someone learning the
topic with a list of unfamiliar assertions and no mental model to hang them on.

So every note follows this order:

| Section | Purpose |
|---|---|
| **How to work through this note** | An explicit path. Which test to run first, which section next. The worked examples are the tests, and without this they stay buried behind the prose. |
| **What you will be able to answer afterwards** | Three or four concrete questions. Orientation, not payoff. |
| **Before this note** | Which earlier note to read first, then the **Java** the note leans on, inline and sufficient. No clicking away. |
| *the mechanism* | The body, each section ending in a `→ TestName` pointer. |
| **What this changes for you** | The surprises table, moved here where it can actually land. |
| Review checklist, code path, source map | Unchanged. |

The prerequisites section is the part that is easy to get wrong. It is not a Java tutorial — the
reader is a professional. It is the specific corner of Java the feature sits in, which is usually
one most people have never needed: annotations are interfaces with `CLASS` retention by default,
`Proxy.newProxyInstance` takes interfaces only, `ThreadPoolExecutor` grows only when the queue is
full. Where those claims can be tested, test them — `lab-annotations` has a
`JavaAnnotationBaselineTest` with no Spring in it at all.

---

## Adding a feature

```bash
cp docs/TEMPLATE.md docs/<feature>.md
cp -r labs/lab-events labs/lab-<feature>    # then strip it back
```

Register the module in the root `pom.xml`, add a row to the table above, add an entry to
[`web/src/data/topics.ts`](../web/src/data/topics.ts), and point the note and the lab at each other.

Keep each lab a **separate Maven module**. Isolated contexts, isolated dependencies, and a lab that
needs JPA does not slow down one that needs nothing.

---

## Conventions

- Test names are sentences: `oneBlockedJobStopsEveryOtherScheduledMethod`.
- One claim per test. A test that asserts five things reports one failure.
- Use `Recorder` from `lab-support` to capture what ran, on which thread, and when.
- `@SpringJUnitConfig` with a nested `@Configuration` for plain Framework behaviour;
  `ApplicationContextRunner` when the subject is one of Boot's auto-configured defaults. Keeping
  those apart makes it obvious which layer an opinion comes from.
- Where the subject is a real transaction, use a real database. `labs/lab-transactions` runs against
  an embedded H2 with a real `JdbcTransactionManager`, because a mock transaction manager would let
  a wrong note pass.
- `@DirtiesContext(classMode = AFTER_CLASS)` on any test whose beans keep running after it.

---

## Backlog

Features worth the same treatment, roughly in order of how often they surprise people:

- **`JdbcTemplate` and `DataSource`** — connection handling, pool exhaustion, and how a vendor
  `SQLException` becomes a `DataAccessException`
- **Calling other services** — `RestClient` and HTTP interface clients: the timeouts that are not
  set by default, error decoding, and connection pools
- **Isolation levels** — dirty reads, non-repeatable reads and phantoms, and which level actually
  prevents which, proved against a real database
- **Hibernate's persistence context** — flush timing, dirty checking, lazy loading, and where N+1
  comes from
- **The security filter chain** — where authentication actually happens, and why it being a filter
  rather than an interceptor changes error handling
- **Virtual threads in Boot 4** — what `spring.threads.virtual.enabled` actually swaps out, and
  what still pins a carrier thread
- **`@Configuration(proxyBeanMethods = true)`** — what the CGLIB enhancement does to inter-bean
  method calls, and what it costs
