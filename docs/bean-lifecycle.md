# Bean lifecycle and DI, end to end

Verified against **Spring Framework 7.0.9 / Spring Boot 4.1.1**. Every claim below is pinned by a
test in [`labs/lab-lifecycle`](../labs/lab-lifecycle); if a claim and a test ever disagree, the test
is right.

Third note in the Foundations track. The order below was written by running
`LifecycleOrderTest` and reading the output, not from memory — which is the only way to get it
right, as the note on [reading the source](reading-the-source.md) argues.

---

## The short version

| Assumption | Reality |
|---|---|
| Dependencies are available in the constructor | Only the **constructor-injected** ones |
| `@PostConstruct` runs on the finished bean | It runs **before** the proxy is created |
| Constructor injection is just a style preference | It is the only style that **cannot** form a cycle |
| A prototype is destroyed with the context | It is **never** destroyed. Spring forgets it at handover |
| A prototype injected into a singleton stays fresh | It is resolved **once**, then reused forever |

---

## The order

One method, `AbstractAutowireCapableBeanFactory.doCreateBean`, calls three others. Every callback
you have ever used hangs off one of them.

```
createBeanInstance      constructor
                        postProcessMergedBeanDefinition      @Autowired metadata collected
                        addSingletonFactory                  half-built object published

populateBean            postProcessProperties                field and setter injection

initializeBean          invokeAwareMethods                   BeanName, BeanClassLoader, BeanFactory
                        postProcessBeforeInitialization      other Aware callbacks, @PostConstruct
                        afterPropertiesSet
                        custom init-method
                        postProcessAfterInitialization       the proxy is created HERE

                        afterSingletonsInstantiated          once, after every singleton exists

destroy                 @PreDestroy, DisposableBean.destroy, custom destroy-method
```

Two consequences worth internalising.

**Injection is not special.** It is `InstantiationAwareBeanPostProcessor.postProcessProperties`, and
`AutowiredAnnotationBeanPostProcessor` is simply one such processor. That is why, in the recorded
order, the setter fires *before* a plain unordered `BeanPostProcessor` sees the properties callback
at all: the injecting processor is `PriorityOrdered` and yours is not.

**The proxy appears last.** Everything above `postProcessAfterInitialization` ran on your raw
object. `@PostConstruct` therefore executes on an unproxied bean, so a self-call there gets no
transaction, no caching and no retry — the [proxy model](proxies.md) note explains why.

→ `LifecycleOrderTest`

<!-- widget:lifecycle-timeline -->

---

## Injection styles

| Style | Cycle possible | `final` fields | Testable without Spring |
|---|---|---|---|
| Constructor | no | yes | yes |
| Setter | yes | no | yes |
| Field | yes | no | needs reflection |

A single constructor needs no `@Autowired`. Optional dependencies have three spellings, all of
which behave rather than fail:

```java
@Autowired(required = false) Optional<Thing> maybe;   // empty
@Autowired ObjectProvider<Thing> provider;            // getIfAvailable() -> null
@Autowired(required = false) List<Thing> all;         // empty list, never null
```

`ObjectProvider` is also the fix for the prototype-in-a-singleton problem below, because it defers
the lookup to call time.

→ `InjectionAndCyclesTest`

---

## Circular references

Constructor injection **cannot** form a cycle: neither object can be constructed before the other,
so Spring fails with "Is there an unresolvable circular reference?".

Field and setter injection **can**, because `addSingletonFactory` publishes a reference to the
half-built singleton between instantiation and population. The other bean receives an object whose
fields are not yet set. It works, and it means one of your beans was handed something unfinished.

Spring Boot turns this off: `spring.main.allow-circular-references=false` has been the default since
2.6, which converts a fragile-but-working application into a startup failure. The plain container
still allows it, which is why a cycle can pass in a `@SpringJUnitConfig` test and fail in the app.

The fix is almost never `@Lazy`. A cycle is a design signal: extract the shared behaviour into a
third bean, or make one side event-driven.

→ `InjectionAndCyclesTest`

---

## Scopes

**Singletons** are created eagerly during refresh, before anyone asks, and destroyed with the
context.

**Prototypes** are created per lookup and **never destroyed**. Spring stops tracking the instance
the moment it hands it over, so `@PreDestroy` never runs. Anything the bean holds open — a
connection, a file, an executor — is yours to close.

**A prototype injected into a singleton is resolved once.** The singleton is built once, so its
dependency is resolved once, and your "prototype" is a singleton for the rest of the run. This is
one of the most common quiet bugs in Spring applications. Inject `ObjectProvider<T>` and call
`getObject()` when you actually need one.

`@Lazy` defers creation until first use. Useful for something expensive that is rarely touched;
not a good tool for hiding a circular reference.

→ `ScopeAndLazyTest`

---

## `BeanPostProcessor` ordering

Post-processors run in three groups: `PriorityOrdered` first, then `Ordered`, then everything else
in registration order. Within the first two, lower values win.

This matters because the auto-proxy creator is itself a post-processor. Anything ordered **before**
it sees the raw bean; anything **after** sees the proxy. `ScheduledAnnotationBeanPostProcessor`
declares `LOWEST_PRECEDENCE` for exactly this reason — it needs the proxy so that `@Scheduled` and
`@Transactional` compose.

One practical note: declare `BeanPostProcessor` `@Bean` methods `static`. A non-static one forces
its `@Configuration` class to be instantiated very early, before it can be post-processed itself,
and Spring logs a warning about it.

→ `ScopeAndLazyTest`

---

## Review checklist

- [ ] Does anything use a field-injected dependency from the constructor? It is null there.
- [ ] Does a `@PostConstruct` method call another method on the same bean expecting advice?
- [ ] Any circular reference — and does it pass only because this is a plain context, not Boot?
- [ ] Any prototype holding a resource, expecting `@PreDestroy` to close it?
- [ ] Any prototype injected directly into a singleton rather than through `ObjectProvider`?
- [ ] Any `BeanPostProcessor` `@Bean` method that is not `static`?
- [ ] Constructor injection for required dependencies, so the fields can be `final`?

---

## The code path

<!-- widget:path:bean-creation -->

---

## Source map

`spring-beans`, almost entirely.

| Class | Role |
|---|---|
| `support.AbstractAutowireCapableBeanFactory` | `doCreateBean` and the three phases |
| `support.DefaultSingletonBeanRegistry` | the singleton caches and early exposure |
| `annotation.AutowiredAnnotationBeanPostProcessor` | `@Autowired` metadata and injection |
| `annotation.InitDestroyAnnotationBeanPostProcessor` | `@PostConstruct` and `@PreDestroy` |
| `support.DisposableBeanAdapter` | shutdown, in mirror order |
| `context.support.ApplicationContextAwareProcessor` | the context-level `Aware` callbacks |
