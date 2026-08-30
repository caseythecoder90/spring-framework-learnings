# `@Async` and executors, end to end

Verified against **Spring Framework 7.0.9 / Spring Boot 4.1.1**. Every claim below is pinned by a
test in [`labs/lab-async`](../labs/lab-async); if a claim and a test ever disagree, the test is
right.

This completes the threading story started in [scheduling](scheduling.md) and
[events](events.md). It is also the third feature in this repo that is an interceptor on a proxy,
so if [the proxy model](proxies.md) is fresh, most of this is already familiar.

---

## How to work through this note

1. **Read "Before this note".** `ThreadPoolExecutor`'s growth rule is the whole of the second half
   of this note, and it is a JDK behaviour rather than a Spring one.
2. **Run `AsyncExecutorResolutionTest` and read it.**
   ```bash
   ./mvnw -pl labs/lab-async -am test -Dtest=AsyncExecutorResolutionTest
   ```
   Which executor your method actually runs on, including the alarming default.
3. **Read "Which executor runs it".**
4. **Run and read `ThreadPoolBehaviourTest`**, then "The queue fills before the pool grows". If you
   read one section of this note, make it that one.
5. **Run and read `AsyncSemanticsTest`**, then "What you give up".
6. **Finish with "What this changes for you."**

---

## What you will be able to answer afterwards

- Which thread pool does an `@Async` method use when I have not configured one?
- Why does raising `max-size` on my executor change nothing at all?
- Where does the exception go when an `@Async` method throws?
- Why is there no transaction, no security context and no MDC on the async thread?

---

## Before this note

**Read [the proxy model](proxies.md) first** — `@Async` is an interceptor and fails the same way as
every other one. [Scheduling](scheduling.md) is a useful companion: same package, same executor
concepts, different trigger.

**The Java you need.** All of it `java.util.concurrent`:

*`ExecutorService` accepts work and returns a `Future`.* `Future.get()` blocks until the result
exists and rethrows any failure wrapped in an `ExecutionException`. `CompletableFuture` adds
composition — `thenApply`, `thenCompose`, `exceptionally` — without changing that basic contract.

*A `void` task has nowhere to put an exception.* If nothing holds a `Future`, a thrown exception has
no destination: the thread dies quietly, or an uncaught-exception handler logs it. That is not a
Spring design choice, it is the shape of the problem, and it is why the return type of an `@Async`
method decides where its exceptions end up.

*`ThreadPoolExecutor` grows only when the queue is full.* This is the one to actually memorise:

```
task arrives
  ├─ fewer threads than core?      -> start a new thread
  ├─ otherwise, can the queue take it?  -> queue it
  └─ queue full and below max?     -> start a new thread
```

Read it twice. With an **unbounded** queue the third branch is unreachable, so `maximumPoolSize` is
dead configuration and the pool never exceeds its core size. Spring does not change this; it just
inherits it, and Boot's default queue is unbounded.

*`ThreadLocal` does not cross threads.* Same fact as in the events note, and the reason a
transaction does not follow the hand-off.

---

## Which executor runs it

`AsyncExecutionAspectSupport.determineAsyncExecutor`, resolved once per method and then cached:

1. `@Async("reportExecutor")` → that bean, by qualifier.
2. Otherwise the `AsyncConfigurer`'s executor, if you supplied one.
3. Otherwise a unique `TaskExecutor` bean, or one named **`taskExecutor`**
   (`DEFAULT_TASK_EXECUTOR_BEAN_NAME` — the same magic name scheduling uses, from a different
   constant).
4. Otherwise **`new SimpleAsyncTaskExecutor()`**.

Step 4 is the one to know. `SimpleAsyncTaskExecutor` does not pool: it starts a fresh platform
thread for every call and has no upper bound. Under load that is a thread-per-request machine with
no back-pressure, and the only sign is thread names reading `SimpleAsyncTaskExecutor-1`, `-2`,
`-3`...

Spring Boot papers over this by contributing `applicationTaskExecutor`, so a Boot application does
get a pool. A plain `@EnableAsync` context does not.

→ `AsyncExecutorResolutionTest`

---

## The queue fills before the pool grows

This is not Spring's rule, it is `ThreadPoolExecutor`'s, and it is the most expensive thing on this
page:

```
task arrives
  ├─ fewer threads than core?      -> start a new thread
  ├─ otherwise, can the queue take it?  -> queue it
  └─ queue full and below max?     -> start a new thread
```

The pool only grows past core **when the queue is full**. Boot's defaults:

```yaml
spring:
  task:
    execution:
      pool:
        core-size: 8
        max-size: 2147483647        # Integer.MAX_VALUE
        queue-capacity: 2147483647  # Integer.MAX_VALUE
```

With an unbounded queue the queue is never full, so `max-size` is **dead configuration**. A pool
described as "core 8, max 200" is a pool of 8 threads with an unbounded backlog. Raising `max-size`
to fix a throughput problem changes nothing at all; you have to bound the queue first.

Bounding the queue also gives you back-pressure: when it fills and the pool is at max, the
`RejectedExecutionHandler` fires instead of memory quietly filling with queued work.

→ `ThreadPoolBehaviourTest`

---

## What you give up

**The exception, if the method returns `void`.** It goes to the `AsyncUncaughtExceptionHandler`,
captured when the advisor was built. The default implementation logs and does nothing else. Return
`CompletableFuture` and the exception comes back to whoever calls `get()` or `join()`:

```java
@Async CompletableFuture<Report> build();   // exception reaches the caller
@Async void build();                        // exception reaches a log line
```

**Every thread-local.** The transaction (`TransactionSynchronizationManager`), `SecurityContext`,
MDC and request scope are all thread-bound, and this is a different thread. A `@Transactional`
method calling an `@Async` method does not extend its transaction into it — the async work runs
outside, and may well run after the original transaction committed.

Boot 4 has `spring.task.execution.propagate-context` for wiring in context propagation, but treat
propagation as something you opt into deliberately rather than assume.

**And self-invocation, again.** An internal call runs inline on the caller's thread. Same cause as
`@Transactional` and `@Cacheable`; see [the proxy model](proxies.md).

→ `AsyncSemanticsTest`

---

## What this changes for you

Now that the mechanism is in place, the short version — the things that are true and
surprise people:

| Assumption | Reality |
|---|---|
| No executor configured means a sensible default | It means a **new unbounded thread per call** |
| `max-size: 200` gives me up to 200 threads | Not with the default queue. The pool **never grows** |
| The exception will surface somewhere | On a `void` method it goes to a handler that only logs |
| The transaction carries across | It does not. Neither does `SecurityContext` or MDC |
| `@Async` on an internal call still works | It runs inline, synchronously, silently |

---

## Review checklist

- [ ] Is there an executor bean at all, or is this silently `SimpleAsyncTaskExecutor`?
- [ ] Is the queue bounded? If not, `max-size` is doing nothing.
- [ ] `void` or `CompletableFuture` — and if `void`, is there a real uncaught-exception handler?
- [ ] Does the method rely on a transaction, `SecurityContext` or MDC set by the caller?
- [ ] Is the method public, non-final, and called from outside the bean?
- [ ] What happens on shutdown? Is `await-termination` set for work that must finish?
- [ ] On Java 21, would virtual threads suit this better than a pool?

---

## Reading the source yourself

Everything named below is already on your machine, but as compiled jars. Unpack the sources once —
they land in `.spring-sources/`, which is gitignored:

```bash
./tools/fetch-spring-sources.sh spring-aop spring-context
```

Now you can grep them, which is faster than any IDE search:

```bash
grep -rn "class AsyncAnnotationBeanPostProcessor" .spring-sources/
```

**Then walk the path below, in order.** It is not a list of classes to read in full. Each stop names
one method and one thing to notice, and that is all you need from it — most of these classes are
hundreds of lines you can safely ignore.

Do it once with a debugger rather than by reading. Set a breakpoint where the path says to start,
run the lab test for this note, and step through. One pass is worth more than an hour of reading,
because you see the real values.

New to this? [How to read Spring source](reading-the-source.md) is the general method — how to find
the entry point for any feature, and the five shapes it will turn out to be.

<!-- widget:path:async-invocation -->

**You have understood this when you can say, without looking:** where a `void` method's exception
ends up, and why the return type decides that.

---

## The classes involved

For reference later. The ordered walk is above; this is the same material as a lookup table.

| Class | Role |
|---|---|
| `scheduling.annotation.AsyncAnnotationBeanPostProcessor` | what `@EnableAsync` registers |
| `scheduling.annotation.AsyncAnnotationAdvisor` | builds the advice and captures the handler |
| `aop.interceptor.AsyncExecutionInterceptor` | the interception, and the `SimpleAsyncTaskExecutor` fallback |
| `aop.interceptor.AsyncExecutionAspectSupport` | executor resolution, submission, error handling |
| `aop.interceptor.SimpleAsyncUncaughtExceptionHandler` | the default that only logs |
| `boot.autoconfigure.task.TaskExecutionAutoConfiguration` | Boot's `applicationTaskExecutor` |
