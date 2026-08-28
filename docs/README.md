# Notes

One note per Spring feature. Each one is paired with a lab module whose tests prove what the note
claims, so the notes cannot quietly rot: `mvn test` fails when Spring stops behaving the way the
note says it does.

| Note | Lab | Covers |
|---|---|---|
| [scheduling.md](scheduling.md) | [`labs/lab-scheduling`](../labs/lab-scheduling) | `@EnableScheduling`, `@Scheduled`, the pool of one, `fixedRate` vs `fixedDelay`, error suppression, scheduler routing |
| [events.md](events.md) | [`labs/lab-events`](../labs/lab-events) | `ApplicationEventPublisher`, `@EventListener`, ordering and conditions, `@Async`, `@TransactionalEventListener` |

Version under study: **Spring Framework 7.0.9 / Spring Boot 4.1.1**, pinned in the root `pom.xml`.

---

## How a note gets written

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

This project has already produced three of those:

- The `SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME` **value** changed in Framework 7. Code that
  hard-codes the string instead of the constant breaks on upgrade.
- `EventListenerMethodProcessor` scans bean *definitions*, so `@Lazy` beans do get their listeners
  registered — but prototype-scoped beans get a fresh instance per event.
- Two `@TransactionalEventListener` methods in the same phase with no `@Order` run in an
  unspecified order. The first draft of that test asserted an order that happened to hold once.

---

## Adding a feature

```bash
cp docs/TEMPLATE.md docs/<feature>.md
cp -r labs/lab-events labs/lab-<feature>    # then strip it back
```

Register the module in the root `pom.xml`, add a row to the table above, and point the note and the
lab at each other.

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
- `@DirtiesContext(classMode = AFTER_CLASS)` on any test whose beans keep running after it.

---

## Backlog

Features worth the same treatment, roughly in order of how often they surprise people:

- `@Async` and `AsyncConfigurer` in their own right (thread pools, `CompletableFuture`, exceptions)
- `@Transactional`: propagation, self-invocation, proxy modes
- Bean lifecycle and `BeanPostProcessor` ordering
- `@Configuration` `proxyBeanMethods` and what the CGLIB enhancement actually does
- Conditional evaluation: `@Conditional`, auto-configuration ordering, `ApplicationContextRunner`
- `ApplicationContext` startup phases and `SmartLifecycle`
- Caching: `@Cacheable` key generation and the self-invocation problem again
- Virtual threads in Boot 4: what `spring.threads.virtual.enabled` actually swaps out
