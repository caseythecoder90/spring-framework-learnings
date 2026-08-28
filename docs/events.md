# `ApplicationEventPublisher`, end to end

Verified against **Spring Framework 7.0.9 / Spring Boot 4.1.1**. Every claim below is pinned by a
test in [`labs/lab-events`](../labs/lab-events); if a claim and a test ever disagree, the test is
right.

---

## The short version

| Assumption people make | Reality |
|---|---|
| Publishing is fire-and-forget | It is a **synchronous method call** on the publishing thread |
| Listeners are isolated | The first one to throw **breaks the publisher** and skips the rest |
| It decouples components | It decouples *compilation*. At runtime it is still one call stack |
| Events are ordered by registration | Unordered unless you add `@Order` |
| `@TransactionalEventListener` always fires | With **no active transaction it is silently skipped** |

The mental model that keeps you out of trouble: `publishEvent` is a `for` loop over listeners with
extra type matching. Nothing more.

---

## The publish path

`ApplicationEventPublisher` is injectable anywhere, with no bean definition for it, because
`AbstractApplicationContext.prepareBeanFactory` calls
`registerResolvableDependency(ApplicationEventPublisher.class, this)`. The context injects itself.

`AbstractApplicationContext.publishEvent(Object event, ResolvableType typeHint)` then does four
things, in order:

1. **Wrap.** If the event is not an `ApplicationEvent`, it becomes
   `new PayloadApplicationEvent<>(this, event, payloadType)`. This is why a plain record works as an
   event, and why `getSource()` on the wrapper is the application context rather than your service.
2. **Buffer, maybe.** If `earlyApplicationEvents != null` — meaning the context has not reached
   `registerListeners()` yet — the event is parked and replayed later. Events published from a
   `BeanPostProcessor` or a constructor land here.
3. **Multicast.** `applicationEventMulticaster.multicastEvent(event, eventType)`.
4. **Propagate to the parent.** If there is a parent context, the event is republished there. Note
   the direction: events bubble **up**, never down. In a Boot app with a parent context (Spring
   Cloud, some test setups), a listener in the child will not see an event published by the parent.

Then `SimpleApplicationEventMulticaster.multicastEvent`:

```java
Executor executor = getTaskExecutor();
for (ApplicationListener<?> listener : getApplicationListeners(event, type)) {
    if (executor != null && listener.supportsAsyncExecution()) {
        executor.execute(() -> invokeListener(listener, event));
    }
    else {
        invokeListener(listener, event);   // same thread, same transaction, same stack
    }
}
```

Two fields decide everything, and **both are null by default**:

- `taskExecutor` is null → every listener runs inline on the publishing thread.
- `errorHandler` is null → `invokeListener` does not catch anything, so a listener exception
  propagates straight out of `publishEvent` into the business method that called it.

Setting `taskExecutor` on the multicaster makes *every* listener in the application async at once.
That is almost never what you want; `@Async` on the individual listener is the tool for the job.

→ `SynchronousPublishTest`

---

## From annotation to listener

`@EventListener` methods are adapted by `EventListenerMethodProcessor`, registered under
`AnnotationConfigUtils.EVENT_LISTENER_PROCESSOR_BEAN_NAME`.

It is a `SmartInitializingSingleton`, so it runs once, after every singleton exists. It walks
`beanFactory.getBeanNamesForType(Object.class)` and, for each annotated method, asks an
`EventListenerFactory` to build an `ApplicationListenerMethodAdapter`.

Two consequences that are easy to get wrong:

- It works off **bean definitions**, not instances. A `@Lazy` bean still gets its listeners
  registered; `ApplicationListenerMethodAdapter` calls `getBean(beanName)` only when a matching
  event actually arrives, and that is what instantiates it.
- On a **prototype-scoped** bean, that same lookup produces a brand new instance for every event.
  Any state the listener accumulates is thrown away.

Which factory is used matters:

| Factory | Registered by | Produces |
|---|---|---|
| `DefaultEventListenerFactory` | always | a plain listener |
| `RestrictedTransactionalEventListenerFactory` | `@EnableTransactionManagement` | `@TransactionalEventListener` support |

That second row is a real trap: **without transaction management enabled, a
`@TransactionalEventListener` is adapted as an ordinary listener** and its phase semantics quietly
do not apply.

→ `ListenerRegistrationTest`

---

## Listener selection, ordering, conditions, return values

**Selection.** `AbstractApplicationEventMulticaster` matches listeners by `ResolvableType` and
caches the result in a `ListenerRetriever` keyed by (event type, source type). The cache is why
dispatch is cheap after the first event of a given type.

The matching is generic-aware, which is also where it fails. `ResolvableType.forInstance(event)`
cannot recover a type argument that erasure removed, so:

```java
publisher.publishEvent(new EntityChanged<>(order));   // EntityChanged<Order>
@EventListener void on(EntityChanged<Order> event) {} // may never match
```

The fixes are to implement `ResolvableTypeProvider` on the event class, or to publish with an
explicit type hint. A non-generic event class per domain concept sidesteps the whole problem and is
usually the better answer.

**Ordering.** `@Order` on the listener method. Without it the order is unspecified — and since
listeners run on one thread, "unspecified order" plus "the first exception wins" is a genuine
coupling risk between listeners that were supposed to be independent.

**Conditions.** `condition` is SpEL evaluated *before* invocation, against the event:

```java
@EventListener(condition = "#payment.amount() > 100")
void onLargePayment(Payment payment) { ... }
```

**Return values.** A non-null return value is **published as a new event**. Arrays and `Collection`s
are fanned out into one event per element. Convenient for small pipelines, and an easy way to build
an infinite loop; `void` is the safe default.

→ `ListenerRegistrationTest`

---

## Going async, and what you give up

```java
@Async
@EventListener
public void onOrderPlaced(OrderPlaced event) { ... }
```

`@Async` needs `@EnableAsync`, and the bean gets wrapped in a proxy — so the method must be
**public and non-final**, and the proxy is created *without calling the constructor*, meaning
fields read off an injected reference are null. (Call methods, not fields.)

What changes:

- The publisher returns immediately.
- **The exception no longer reaches the publisher.** For a `void` method it goes to the
  `AsyncUncaughtExceptionHandler`, whose default implementation only logs. If the listener does
  real work, install your own handler.
- **The transaction does not travel with it.** The listener runs on a different thread, so
  `TransactionSynchronizationManager` is empty. Anything relying on thread-locals — transaction,
  `SecurityContext`, MDC, request scope — has to be propagated deliberately.
- Rejected execution falls back to running inline: `multicastEvent` catches
  `RejectedExecutionException` and invokes the listener on the publishing thread anyway. A saturated
  pool degrades to synchronous rather than dropping the event.

→ `AsyncListenerTest`

---

## Transactional listeners

`@TransactionalEventListener` does not listen for the event directly. It registers a
`TransactionSynchronization` on the current transaction and lets that fire the callback:

```
publishEvent
  └─ TransactionalApplicationListenerMethodAdapter.onApplicationEvent
       ├─ transaction active?  -> register synchronization, return    (nothing runs yet)
       ├─ fallbackExecution?   -> run immediately
       └─ neither              -> log "No transaction is active - skipping" at DEBUG, return
```

Phases: `BEFORE_COMMIT`, `AFTER_COMMIT` (the default), `AFTER_ROLLBACK`, `AFTER_COMPLETION`.

Observed ordering inside a `@Transactional` service method:

```
service-begin
plain @EventListener        <- inline, inside the transaction
service-end                 <- the method returns
                            <- commit happens here, in the proxy
@TransactionalEventListener <- now
```

The third branch above is the one that causes production incidents. The **same listener** works when
called from a `@Transactional` service and silently does nothing when called from a scheduled job, a
`@PostConstruct`, or a test that forgot the transaction. The only trace is a DEBUG log nobody has
enabled. `fallbackExecution = true` opts out of that behaviour.

Also worth knowing: writing to the database from an `AFTER_COMMIT` listener needs
`REQUIRES_NEW`, because the original transaction is already finished. Framework's
`RestrictedTransactionalEventListenerFactory` exists to catch exactly that mistake.

→ `TransactionalEventListenerTest`

---

## When not to use events

Application events are an in-process, in-transaction, synchronous callback mechanism. They are a
good fit for decoupling a side effect from a core operation *inside one application*.

They are the wrong tool when you need:

- **delivery guarantees** — an event is lost if the process dies mid-listener; use an outbox table
  or a broker;
- **cross-service communication** — this never leaves the JVM;
- **backpressure or retry** — there is none;
- **auditability** — there is no record that an event was published.

The honest summary of what you get is compile-time decoupling. The call stack stays exactly as
coupled as it was.

---

## Review checklist

- [ ] Does the publisher rely on the listener having finished? (It has — that is synchronous.)
- [ ] What happens to the caller if a listener throws?
- [ ] Is there an implicit ordering dependency between listeners with no `@Order`?
- [ ] `@TransactionalEventListener` — is there guaranteed to be a transaction at every call site?
- [ ] `@Async` — where does the exception go, and does the listener need the transaction?
- [ ] Does any listener return a value, and is that intentional?
- [ ] Would a direct method call be clearer than an event here?

---

## Source map

`spring-context`, plus `spring-tx` for the transactional part. Run
[`tools/fetch-spring-sources.sh`](../tools/fetch-spring-sources.sh) to read along.

| Class | Role |
|---|---|
| `context.support.AbstractApplicationContext#publishEvent` | wrap, buffer, multicast, propagate to parent |
| `context.PayloadApplicationEvent` | the wrapper around non-`ApplicationEvent` payloads |
| `context.event.SimpleApplicationEventMulticaster` | the dispatch loop; `taskExecutor` and `errorHandler` |
| `context.event.AbstractApplicationEventMulticaster` | listener matching and the retriever cache |
| `context.event.EventListenerMethodProcessor` | turns annotated methods into listeners |
| `context.event.ApplicationListenerMethodAdapter` | invocation, conditions, return-value republishing |
| `transaction.event.TransactionalApplicationListenerMethodAdapter` | the phase and fallback logic |
| `transaction.event.RestrictedTransactionalEventListenerFactory` | registered by `@EnableTransactionManagement` |
