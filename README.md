# spring-framework-learnings

Notes on how Spring features actually work under the hood, where every claim in the notes is pinned
by a test that fails if it stops being true.

The premise: it is very easy to write a confident, plausible, wrong explanation of a Spring
internal. So each feature gets two things — a note written after reading the real source, and a lab
module whose tests prove what the note says. `mvn test` is the fact-checker.

Studying **Spring Framework 7.0.9 / Spring Boot 4.1.1** on Java 21.

---

## Layout

```
docs/                 the notes, one per feature
  scheduling.md         @Scheduled, end to end
  events.md             ApplicationEventPublisher, end to end
  TEMPLATE.md           skeleton for the next feature
labs/
  lab-support/          Recorder: what ran, on which thread, when
  lab-scheduling/       14 tests + a runnable demo app
  lab-events/           15 tests + a runnable demo app
tools/
  fetch-spring-sources.sh   unpack Spring source jars locally to read along
```

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

To read the implementation alongside the notes:

```bash
./tools/fetch-spring-sources.sh
grep -rn "class SimpleApplicationEventMulticaster" .spring-sources/
```

---

## What is in here so far

**[`docs/scheduling.md`](docs/scheduling.md)** — `@EnableScheduling` wiring, the six-step
`TaskSchedulerRouter` lookup, Boot's one-thread default pool and how it starves unrelated jobs, why
`fixedRate` silently falls behind, why a failing job keeps its schedule and how to find out that it
is failing, and why `@Scheduled` + `@Transactional` works when so many proxy combinations do not.

**[`docs/events.md`](docs/events.md)** — what `publishEvent` really does (wrap, buffer, multicast,
bubble to the parent), why it is synchronous and unisolated, how `@EventListener` methods get
registered and matched, what `@Async` costs you, and the `@TransactionalEventListener` behaviour
that silently does nothing when there is no transaction.

Each note ends with a review checklist and a map of the classes to read.

---

## Ground rules

- Read the source before writing the prose. The reference docs describe intent; the source describes
  behaviour, and the gap is the interesting part.
- One claim per test, named as a sentence.
- No `Thread.sleep` where a latch will do. Where timing is genuinely the subject, assert lower
  bounds and differences, not exact numbers, so the suite survives a busy laptop.
- Write down what was surprising. A note that restates the reference documentation is not worth
  keeping.

More detail, and the backlog of features to cover next, in [`docs/README.md`](docs/README.md).
