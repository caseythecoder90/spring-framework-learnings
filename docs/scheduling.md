# `@Scheduled`, end to end

Verified against **Spring Framework 7.0.9 / Spring Boot 4.1.1**. Every claim below is pinned by a
test in [`labs/lab-scheduling`](../labs/lab-scheduling); if a claim and a test ever disagree, the
test is right.

---

## How to work through this note

1. **Read "Before this note".** Mostly `ScheduledExecutorService`, which is what Spring is wrapping
   and where the surprising timing behaviour actually comes from.
2. **Run `SchedulerWiringTest` and read it.**
   ```bash
   ./mvnw -pl labs/lab-scheduling -am test -Dtest=SchedulerWiringTest
   ```
   What `@EnableScheduling` registers, and what each annotated method becomes.
3. **Read "From annotation to running task" and "Which scheduler actually runs it"**, using the
   interactive lookup below.
4. **Run and read `SingleThreadStarvationTest`.** This is the production bug the note exists for.
   Then read "The pool of one".
5. **Run and read `FixedRateVsFixedDelayTest`** and `ScheduledErrorHandlingTest`, with the matching
   sections.
6. **Finish with "What this changes for you."**

---

## What you will be able to answer afterwards

- Why did my unrelated scheduled job stop running when a different one got slow?
- What is the real difference between `fixedRate` and `fixedDelay` when the work is slow?
- What happens to a scheduled method that throws — does it keep running?
- Which thread pool is my job actually on, and how many threads does it have?

---

## Before this note

**No earlier note is required**, though [the proxy model](proxies.md) explains why `@Scheduled` and
`@Transactional` compose correctly on the same method.

**The Java you need.** Spring's scheduling is a thin layer over
`java.util.concurrent.ScheduledExecutorService`, and the two behaviours people find surprising are
the JDK's, not Spring's:

```java
ScheduledExecutorService pool = Executors.newScheduledThreadPool(1);
pool.scheduleAtFixedRate(task, 0, 1, SECONDS);       // start to start
pool.scheduleWithFixedDelay(task, 0, 1, SECONDS);    // end to start
```

*Fixed rate measures start-to-start; fixed delay measures end-to-start.* If the work takes longer
than the interval, fixed rate cannot keep up — and because the executor never runs the same task
concurrently with itself, late executions queue and then run back to back. Spring's `fixedRate` and
`fixedDelay` are these two, renamed.

*A pool of N runs N tasks at once, and everything else waits.* A `ScheduledExecutorService` with one
thread runs one task at a time, no matter how many are due. Nothing warns you that the others are
queued; they are simply late.

*An exception kills a repeating task.* This is the important one. If a task submitted to
`scheduleAtFixedRate` throws, the executor **cancels that schedule permanently** — silently, with no
further executions ever. Spring wraps every scheduled method to stop that from happening, which is
why a failing job keeps running instead, and why the failure is only a log line.

---

## From annotation to running task

`@EnableScheduling` is a one-line `@Import`:

```
@EnableScheduling
  └─ @Import(SchedulingConfiguration.class)
       └─ @Bean(name = "org.springframework.scheduling.config.internalScheduledAnnotationProcessor")
            ScheduledAnnotationBeanPostProcessor
```

> **Framework 7 changed the value of that bean name.** On Boot 3.x it was
> `org.springframework.context.annotation.internalScheduledAnnotationProcessor`. Use the constant
> `TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME`, never the string.

`ScheduledAnnotationBeanPostProcessor` (SABPP) does its work in two phases.

**Phase 1 — `postProcessAfterInitialization`, once per bean.** It takes
`AopProxyUtils.ultimateTargetClass(bean)`, looks for `@Scheduled` on every method, and converts
each hit into a `Task`:

| Annotation | Task type |
|---|---|
| `cron = "..."` | `CronTask` |
| `fixedDelay = ...` | `FixedDelayTask` |
| `fixedRate = ...` | `FixedRateTask` |

The `Runnable` inside each `Task` is a `ScheduledMethodRunnable` bound to **the object SABPP was
handed**. That matters — see [Proxies](#proxies-scheduled-with-transactional).

**Phase 2 — `ContextRefreshedEvent`.** Tasks are collected, not scheduled, during phase 1. Only
when the context finishes refreshing does `finishRegistration()` run: it resolves a `TaskScheduler`,
applies every `SchedulingConfigurer`, and calls `ScheduledTaskRegistrar.afterPropertiesSet()`,
which finally hands the tasks to the scheduler.

So nothing fires until the context is fully up. That is why a `@Scheduled` method never races with a
half-initialised application, and why registration problems surface at startup rather than at the
first trigger.

→ `SchedulerWiringTest`

---

## Which scheduler actually runs it

Since Framework 6.1 the lookup goes through `TaskSchedulerRouter`, resolved **per task**:

1. `@Scheduled(scheduler = "someBean")` → that bean, by qualifier.
2. Otherwise the unique `TaskScheduler` bean by type. A `@Primary` bean counts as unique.
3. If several exist, the one **named `taskScheduler`**. If none is, the search **stops here** and
   falls to step 6 — the `ScheduledExecutorService` branch below is never reached.
4. If there was no `TaskScheduler` bean at all, a unique `ScheduledExecutorService`, wrapped in a
   `ConcurrentTaskScheduler`.
5. If several of those exist, again the one named `taskScheduler`.
6. **Otherwise `Executors.newSingleThreadScheduledExecutor()`** — a private, single-threaded pool,
   announced only by an INFO line: *No TaskScheduler/ScheduledExecutorService bean found for
   scheduled processing*.

Steps 3 and 6 are the ones worth knowing by heart. A context with two `TaskScheduler` beans and
neither named `taskScheduler` lands on a private single thread, and the only warning is an INFO log.

<!-- widget:scheduler-routing -->

Step 1 is also the cheapest fix for pool starvation: leave the shared pool alone and give the slow
job its own.

```java
@Scheduled(fixedDelay = 60_000, scheduler = "reportScheduler")
public void rebuildReports() { ... }
```

→ `SchedulerRoutingTest`

---

## The pool of one

Boot's `TaskSchedulingAutoConfiguration` is `@ConditionalOnBean(name = <the SABPP bean name>)`, so
there is **no `TaskScheduler` at all** until something switches scheduling on. Once it does:

```yaml
spring:
  task:
    scheduling:
      pool:
        size: 1              # <- the default
      thread-name-prefix: "scheduling-"
```

`spring.task.scheduling.pool.size` defaults to **1**. Every `@Scheduled` method in the application
shares that one thread. A job that takes four seconds does not merely run slowly — for four seconds
nothing else scheduled runs at all, and the missed runs pile up behind it.

The failure mode in production is nasty because it is indirect: the job you notice as broken is
never the job that is actually slow.

Ways out, roughly in order of preference:

1. Give the slow job its own scheduler (`@Scheduled(scheduler = ...)`).
2. Raise `spring.task.scheduling.pool.size` above the number of jobs that can overlap.
3. Make the job hand its work to a separate executor and return immediately.
4. On Java 21, `spring.threads.virtual.enabled=true` swaps in a `SimpleAsyncTaskScheduler` backed by
   virtual threads — effectively unbounded concurrency, capped by
   `spring.task.scheduling.simple.concurrency-limit` if you set it. Cheap for I/O-bound jobs, no
   help for CPU-bound ones, and it removes the back-pressure a fixed pool was giving you.

→ `SingleThreadStarvationTest`, `BootSchedulerDefaultsTest`

---

## fixedRate vs fixedDelay

```
work = 150ms

fixedRate = 100ms      |--150--|--150--|--150--|      period ~ 150ms  (execution-bound)
fixedDelay = 100ms     |--150--|100|--150--|100|      period ~ 250ms  (work + gap)
```

`fixedRate` targets start-to-start. When the work outlasts the interval it cannot keep up, and
because the underlying `ScheduledThreadPoolExecutor` never runs the same task concurrently, the runs
simply queue back-to-back. The job documented as "every minute" is now "every three minutes", and
nothing anywhere reports that.

`fixedDelay` targets end-to-start, so the real period is always `work + delay`. It cannot fall
behind, because it has no target to fall behind of.

Rule of thumb: **`fixedDelay` unless you genuinely need a cadence.** For a real cadence prefer
`cron`, which is absolute rather than relative and therefore does not drift.

All three accept ISO-8601 strings (`@Scheduled(fixedDelayString = "PT30S")`), which is the form to
use when the value should be configurable rather than compiled in.

→ `FixedRateVsFixedDelayTest`

---

## Failure is silent by design

`TaskUtils.getDefaultErrorHandler(boolean isRepeatingTask)`:

- repeating task → `LOG_AND_SUPPRESS_ERROR_HANDLER`
- one-shot task → `LOG_AND_PROPAGATE_ERROR_HANDLER`

Suppression is not sloppiness. If the exception reached `ScheduledThreadPoolExecutor`, that executor
would cancel the task **permanently** — one transient database blip at 3am and the job is gone until
the next deploy. Spring trades a loud failure for a durable schedule.

The consequence is that a job can fail on every single run and the only evidence is an ERROR line
from `TaskUtils$LoggingErrorHandler`. If a job matters, add one of:

- **A custom `ErrorHandler`**, via `SchedulingConfigurer`:

  ```java
  @Bean
  SchedulingConfigurer errorHandling(MeterRegistry meters) {
      return registrar -> registrar.setErrorHandler(ex -> {
          meters.counter("scheduled.failures").increment();
          log.error("scheduled task failed", ex);
      });
  }
  ```

- **`Task.getLastExecutionOutcome()`** (Framework 6.2+), which reports
  `NONE` / `STARTED` / `SUCCESS` / `ERROR` plus the throwable — enough to build a health indicator
  that answers "did the nightly job actually succeed?":

  ```java
  scheduledTaskHolder.getScheduledTasks().stream()
      .map(task -> task.getTask().getLastExecutionOutcome())
      .filter(outcome -> outcome.status() == TaskExecutionOutcome.Status.ERROR)
  ```

→ `ScheduledErrorHandlingTest`

---

## Proxies: @Scheduled with @Transactional

`ScheduledAnnotationBeanPostProcessor.getOrder()` returns `Ordered.LOWEST_PRECEDENCE`. It therefore
runs **after** the auto-proxy creator, so the object it captures in `ScheduledMethodRunnable` is the
proxy, and the scheduled invocation passes through the full interceptor chain. `@Scheduled` and
`@Transactional` on the same method work.

Two things still bite:

- The method must be **public and non-final** for a CGLIB proxy to intercept it. A package-private
  `@Scheduled` method is still scheduled — it just runs with no transaction around it.
- `@Scheduled` methods must take **no arguments**. SABPP rejects anything else at startup.

---

## It runs on every instance

Nothing in Spring coordinates schedules across a cluster. Three pods means three executions, at the
same moment, racing each other. Spring has no built-in answer; the usual ones are:

- **ShedLock** — a lock row in a database you already have. Least invasive.
- **Quartz** with a JDBC job store — heavier, but real clustering and misfire policies.
- Run the scheduler in exactly one instance, selected by profile or by leader election.

Whichever you pick, decide explicitly what a *missed* run should do. `@Scheduled` has no concept of
a misfire: if the process was down at 03:00, that run simply never happened.

---

## Shutdown

By default the context stops without waiting for a running job — the thread is interrupted
mid-work. For a job that must not be torn open:

```yaml
spring:
  task:
    scheduling:
      shutdown:
        await-termination: true
        await-termination-period: 30s
```

---

## What this changes for you

Now that the mechanism is in place, the short version — the things that are true and
surprise people:

| Thing | Reality |
|---|---|
| Default pool size in Boot | **1 thread for the whole application** |
| A slow job | blocks every other `@Scheduled` method, not just itself |
| `fixedRate` | measured from the **start** of the previous run |
| `fixedDelay` | measured from the **end** of the previous run |
| A job that throws | is logged and **keeps its schedule** — nothing else happens |
| Two app instances | both run the job; Spring has no opinion about that |

---

## Review checklist

Worth asking of any `@Scheduled` method in a code review:

- [ ] How long does it take at p99, and what is the pool size?
- [ ] `fixedRate` or `fixedDelay` — does the choice match the intent?
- [ ] What happens when it throws? Is there a metric, or only a log line?
- [ ] What happens when two instances run it at once? Is it idempotent, or locked?
- [ ] What happens if a run is skipped entirely? Does the next one catch up?
- [ ] Is the interval configurable (`fixedDelayString`) or compiled in?
- [ ] Is the method public, no-arg, and on a bean that is not lazy?

---

## Source map

Everything here lives in `spring-context`. Run
[`tools/fetch-spring-sources.sh`](../tools/fetch-spring-sources.sh) to read along.

| Class | Role |
|---|---|
| `scheduling.annotation.SchedulingConfiguration` | registers the post-processor |
| `scheduling.annotation.ScheduledAnnotationBeanPostProcessor` | scans beans, builds tasks, schedules on refresh |
| `scheduling.config.ScheduledTaskRegistrar` | holds tasks, hands them to the scheduler |
| `scheduling.config.TaskSchedulerRouter` | the six-step scheduler lookup |
| `scheduling.config.Task` / `TaskExecutionOutcome` | task model and last-run introspection |
| `scheduling.support.ScheduledMethodRunnable` | the reflective invocation |
| `scheduling.support.TaskUtils` | the default error handlers |
| `boot.autoconfigure.task.TaskSchedulingAutoConfiguration` | Boot's defaults (pool of 1) |
