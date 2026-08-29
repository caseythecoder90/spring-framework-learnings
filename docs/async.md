# `@Async` and executors, end to end

Verified against **Spring Framework 7.0.9 / Spring Boot 4.1.1**. Every claim below is pinned by a
test in [`labs/lab-async`](../labs/lab-async); if a claim and a test ever disagree, the test is
right.

This completes the threading story started in [scheduling](scheduling.md) and
[events](events.md). It is also the third feature in this repo that is an interceptor on a proxy,
so if [the proxy model](proxies.md) is fresh, most of this is already familiar.

---

## The short version

| Assumption | Reality |
|---|---|
| No executor configured means a sensible default | It means a **new unbounded thread per call** |
| `max-size: 200` gives me up to 200 threads | Not with the default queue. The pool **never grows** |
| The exception will surface somewhere | On a `void` method it goes to a handler that only logs |
| The transaction carries across | It does not. Neither does `SecurityContext` or MDC |
| `@Async` on an internal call still works | It runs inline, synchronously, silently |

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

## Review checklist

- [ ] Is there an executor bean at all, or is this silently `SimpleAsyncTaskExecutor`?
- [ ] Is the queue bounded? If not, `max-size` is doing nothing.
- [ ] `void` or `CompletableFuture` — and if `void`, is there a real uncaught-exception handler?
- [ ] Does the method rely on a transaction, `SecurityContext` or MDC set by the caller?
- [ ] Is the method public, non-final, and called from outside the bean?
- [ ] What happens on shutdown? Is `await-termination` set for work that must finish?
- [ ] On Java 21, would virtual threads suit this better than a pool?

---

## The code path

<!-- widget:path:async-invocation -->

---

## Source map

| Class | Role |
|---|---|
| `scheduling.annotation.AsyncAnnotationBeanPostProcessor` | what `@EnableAsync` registers |
| `scheduling.annotation.AsyncAnnotationAdvisor` | builds the advice and captures the handler |
| `aop.interceptor.AsyncExecutionInterceptor` | the interception, and the `SimpleAsyncTaskExecutor` fallback |
| `aop.interceptor.AsyncExecutionAspectSupport` | executor resolution, submission, error handling |
| `aop.interceptor.SimpleAsyncUncaughtExceptionHandler` | the default that only logs |
| `boot.autoconfigure.task.TaskExecutionAutoConfiguration` | Boot's `applicationTaskExecutor` |
