# Retry and concurrency limits, end to end

Verified against **Spring Framework 7.0.9 / Spring Boot 4.1.1**. Every claim below is pinned by a
test in [`labs/lab-retry`](../labs/lab-retry); if a claim and a test ever disagree, the test is
right.

---

## Read this first if you are on Boot 3.x

**Spring Framework 7 has retry built in.** The `org.springframework.resilience` package ships
`@Retryable`, `@ConcurrencyLimit` and `@EnableResilientMethods` in `spring-context`, and
`org.springframework.core.retry` ships a programmatic `RetryTemplate` in `spring-core`. No extra
dependency.

If your codebase uses the separate `spring-retry` project — which is what almost every Boot 3.x
application does — this is a different annotation with the same name. **Check your imports.** The
attribute that will bite you on a migration is the count:

| | spring-retry | Framework 7 native |
|---|---|---|
| Attribute | `maxAttempts` | `maxRetries` |
| Default | 3 | 3 |
| `= 2` means | 2 total invocations | **3 total invocations** |

`maxRetries` counts retries *after* the first call. Copying a value across without reading it
changes how many times your code runs.

Everything below is the native one.

---

## The short version

| Assumption | Reality |
|---|---|
| `maxRetries = 2` runs the method twice | It runs it **three** times |
| Retry needs a dependency | Not since Framework 7 |
| Retrying inside a transaction retries the query | The transaction is usually **outside** the retry |
| An internal call is retried | It is not. Same proxy rule as everywhere else |
| `@ConcurrencyLimit` rejects excess callers | The default **blocks** them |

---

## Native retry in Framework 7

```java
@Retryable(includes = IOException.class, maxRetries = 3, delay = 100, multiplier = 2, jitter = 50)
public Report fetch() { ... }
```

The attributes, all of which also have a `String` variant (`maxRetriesString`, `delayString`, ...)
so they can be resolved from properties:

| Attribute | Default | Notes |
|---|---|---|
| `includes` / `value` | all | `@AliasFor` each other — see [the annotation model](annotations.md) |
| `excludes` | none | wins over `includes` |
| `predicate` | none | `MethodRetryPredicate`, for "retry only on this SQL state" |
| `maxRetries` | 3 | retries **after** the first attempt |
| `delay` | 1000 | milliseconds before the first retry |
| `multiplier` | 1.0 | 1.0 means a fixed delay |
| `jitter` | 0 | randomises the delay, to avoid a thundering herd |
| `maxDelay` | `Long.MAX_VALUE` | the cap that stops a multiplier running away |
| `timeout` | 0 | overall budget rather than a per-attempt one |

When retries run out, the **original exception** propagates. Nothing is wrapped, so callers
upstream see what they would have seen without retry at all.

→ `NativeRetryTest`

---

## Backoff

`delay` with `multiplier` gives exponential backoff, and `maxDelay` caps it. With
`delay = 100, multiplier = 2` the observed gaps are roughly 100ms, 200ms, 400ms.

Always set `maxDelay` when the multiplier is above 1. And in anything with more than one instance,
set `jitter`: without it, every replica that failed at the same moment retries at the same moment,
which is how a brief outage turns into a self-inflicted stampede.

→ `BackoffAndLimitTest`

---

## Retry and transactions

`@EnableResilientMethods` defaults `order` to `LOWEST_PRECEDENCE - 1`, which puts the retry advice
**outside** most other advice, including transactions. That ordering is what you want:

```
retry
  └─ transaction begins
       └─ your method
     transaction commits or rolls back
```

Each attempt gets a **fresh transaction**. Retrying *inside* a transaction is almost always wrong,
because after the first failure the transaction is already marked rollback-only and every
subsequent attempt fails on commit regardless.

If you ever see the two annotations on the same method and are unsure which is outermost, put a
breakpoint in `ReflectiveMethodInvocation.proceed` and read the chain — see
[the proxy model](proxies.md).

---

## Concurrency limits

```java
@ConcurrencyLimit(4)
public void callFragileDownstream() { ... }
```

Bounds how many callers may be inside the method at once. The default behaviour is to **block** the
excess callers, not reject them, so it is a throttle rather than a circuit breaker. A limit of 1
serialises access completely.

Worth knowing what this is not: no fallback, no half-open state, no failure-rate tracking. For a
real circuit breaker you still want Resilience4j.

→ `BackoffAndLimitTest`

---

## When the annotation cannot reach

`RetryTemplate` in `spring-core` is the programmatic form, and it is the answer for a self-call, a
lambda, or a retry around something you do not own:

```java
new RetryTemplate().execute(() -> client.fetch(id));
```

---

## Review checklist

- [ ] Which `@Retryable` is imported — `org.springframework.resilience` or `org.springframework.retry`?
- [ ] Is the count `maxRetries` or `maxAttempts`, and does the value still mean what it did?
- [ ] Is the operation **idempotent**? Retry turns one call into several.
- [ ] `multiplier` above 1 without a `maxDelay`?
- [ ] More than one instance, and no `jitter`?
- [ ] Retry outside the transaction, so each attempt gets a fresh one?
- [ ] Is anything observing the retries, or do they only show up as latency?
- [ ] Is the method public and called from outside the bean?

---

## The code path

<!-- widget:path:retry-invocation -->

---

## Source map

| Class | Role |
|---|---|
| `resilience.annotation.EnableResilientMethods` | the switch, and the advice ordering |
| `resilience.annotation.ResilientMethodsConfiguration` | registers both post-processors |
| `resilience.annotation.RetryAnnotationBeanPostProcessor` | adds the advisor; publishes retry events |
| `resilience.retry.AbstractRetryInterceptor` | the retry loop |
| `resilience.retry.MethodRetrySpec` | the resolved policy for one method |
| `resilience.retry.MethodRetryPredicate` | the extension point beyond includes and excludes |
| `resilience.annotation.ConcurrencyLimitBeanPostProcessor` | the throttle |
| `core.retry.RetryTemplate` | the programmatic form |
