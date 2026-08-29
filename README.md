# spring-framework-learnings

Notes on how Spring features actually work under the hood, where every claim in the notes is pinned
by a test that fails if it stops being true.

The premise: it is very easy to write a confident, plausible, wrong explanation of a Spring
internal. So each feature gets two things — a note written after reading the real source, and a lab
module whose tests prove what the note says. `mvn test` is the fact-checker.

Studying **Spring Framework 7.0.9 / Spring Boot 4.1.1** on Java 21.

---

Read it online: **https://caseythecoder90.github.io/spring-framework-learnings/**

## Layout

```
docs/                 the notes, one per feature — see docs/README.md for the full index
  reading-the-source.md   how to study Spring source yourself
  TEMPLATE.md             skeleton for the next feature
labs/                 one Maven module per note, and nothing shared but the recorder
  lab-support/          Recorder: what ran, on which thread, when
  lab-annotations/  lab-proxies/  lab-lifecycle/  lab-startup/       foundations
  lab-binding/      lab-environment/  lab-conditions/                configuration
  lab-web/                                                            web
  lab-transactions/                                                    data
  lab-scheduling/   lab-events/  lab-async/  lab-retry/  lab-caching/  execution
  lab-testing/                                                         testing
  lab-codepaths/        resolves every class and method in paths/ against the Spring jars
paths/                code paths: guided source-reading traces, checked by a test
web/                  Astro + React + TypeScript site that renders docs/ and paths/
tools/
  fetch-spring-sources.sh   unpack Spring source jars locally to read along
```

**265 tests across 16 labs.** `./mvnw test` is the fact-checker for every claim in `docs/`.

---

## Running it

```bash
./mvnw test
```

The demo apps are the same behaviour with the logs turned up, which is often the faster way to
*feel* a problem before reading about it:

```bash
./mvnw -pl labs/lab-scheduling -am spring-boot:run -Dspring-boot.run.profiles=demo
```

That one prints a heartbeat every 500ms and a four-second job every three seconds, on a
default-sized pool. Watch the heartbeat stop dead for four seconds, then fire eight times in the
same millisecond as `fixedRate` tries to catch up. Ctrl-C to stop it.

```bash
./mvnw -pl labs/lab-events -am spring-boot:run -Dspring-boot.run.profiles=demo
```

That one prints the thread name at every hop, and prints `placeOrder END` *after* every listener has
finished — because publishing an event is a method call.

The site renders the same markdown notes with interactive explainers embedded in them, plus the
code paths:

```bash
cd web && npm install && npm run dev
```

To read the implementation alongside the notes:

```bash
./tools/fetch-spring-sources.sh
grep -rn "class SimpleApplicationEventMulticaster" .spring-sources/
```

---

## What is in here so far

Six tracks. **Read Foundations first** — nearly every surprise in the other five comes back to the
proxy model or the annotation model, and having those two straight turns most of the rest into "oh,
that again". After that, take whichever track matches what you are working on.

**Foundations** — [the annotation model](docs/annotations.md), [the proxy
model](docs/proxies.md), [bean lifecycle and DI](docs/bean-lifecycle.md), [container
startup](docs/startup.md). How Spring finds an annotation you never wrote, why self-invocation
skips your advice, the exact order of every lifecycle callback, and the bean that is created too
early to be proxied at all.

**Configuration** — [property binding](docs/property-binding.md), [environment and
profiles](docs/environment.md), [conditions and auto-configuration](docs/conditions.md). Where
relaxed binding stops, why the `Environment` is a list rather than a map, and why
`@ConditionalOnMissingBean` is reliable in auto-configuration and a coin toss in your own code.

**Web** — [the request lifecycle](docs/web-mvc.md). Every hook a request passes through, why an
exception in a filter never reaches `@ControllerAdvice`, why an unannotated parameter object is
bound from query parameters rather than the body, and how `@ExceptionHandler` resolution really
picks a method.

**Data** — [`@Transactional`](docs/transactions.md). The seven propagation modes proved by what
survives in a real table, the checked exception that commits, and the caught exception that turns
the outer commit into an `UnexpectedRollbackException`.

**Execution** — [scheduling](docs/scheduling.md), [application events](docs/events.md),
[`@Async`](docs/async.md), [retry](docs/retry.md), [caching](docs/caching.md). Boot's one-thread
scheduler default, why publishing an event is just a method call, the queue that fills before the
pool grows, and the two `@Cacheable` methods that share entries.

**Testing** — [the test context cache](docs/testing.md). What actually decides how long the suite
takes, what is in the cache key, and the commit a `@Transactional` test never performs.

Each note ends with a review checklist and a map of the classes to read.

## Code paths

A code path is a guided source-reading trace: the ordered list of classes a feature actually goes
through, with a reason to stop at each one. They live in [`paths/`](paths) as JSON.

The point is that they are **checked**. `CodePathsAreRealTest` resolves every class and method
against the Spring jars on the classpath, so a study guide pointing at a method that Spring renamed
fails the build instead of quietly misleading you.

Start with [how to read Spring source](docs/reading-the-source.md) for the method, then follow a
path.

## Ground rules

- Read the source before writing the prose. The reference docs describe intent; the source describes
  behaviour, and the gap is the interesting part.
- One claim per test, named as a sentence.
- No `Thread.sleep` where a latch will do. Where timing is genuinely the subject, assert lower
  bounds and differences, not exact numbers, so the suite survives a busy laptop.
- Write down what was surprising. A note that restates the reference documentation is not worth
  keeping.

More detail, and the backlog of features to cover next, in [`docs/README.md`](docs/README.md).
