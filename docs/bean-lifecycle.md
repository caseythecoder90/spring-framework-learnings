# Bean lifecycle and DI, end to end

Verified against **Spring Framework 7.0.9 / Spring Boot 4.1.1**. Every claim below is pinned by a
test in [`labs/lab-lifecycle`](../labs/lab-lifecycle); if a claim and a test ever disagree, the test
is right.

Third note in the Foundations track. The order below was written by running
`LifecycleOrderTest` and reading the output, not from memory — which is the only way to get it
right, as the note on [reading the source](reading-the-source.md) argues.

---

## How to work through this note

1. **Read "Before this note".** Mostly a reminder of when Java assigns what during construction,
   which is the thing that makes field injection's timing make sense.
2. **Run `LifecycleOrderTest` and read its output.**
   ```bash
   ./mvnw -pl labs/lab-lifecycle -am test -Dtest=LifecycleOrderTest
   ```
   It records every callback a bean can receive, in order. The order below was written from that
   output rather than from memory, so read the test first and the prose confirms it.
3. **Read "The order"**, comparing it against what the test recorded.
4. **Run and read `InjectionAndCyclesTest`**, then read "Injection styles" and
   "Circular references". This pair is the practical argument for constructor injection.
5. **Run and read `ScopeAndLazyTest`**, then "Scopes". The prototype behaviour is the part people
   get wrong in production.
6. **Finish with "What this changes for you."**

---

## What you will be able to answer afterwards

- In what order do my constructor, `@Autowired` fields, `@PostConstruct` and `afterPropertiesSet`
  actually run?
- Why is a field-injected dependency `null` when I use it in the constructor?
- Why does constructor injection reject a circular reference when field injection tolerates one?
- Why is my prototype bean's `@PreDestroy` never called?

---

## Before this note

**Read [the proxy model](proxies.md) first.** The proxy is created in the *last* step of the
lifecycle, and several things below only make sense once you know that.

**The Java you need:**

*Construction order.* Java assigns in a fixed sequence: field initialisers and instance blocks run
top to bottom, then the constructor body. By the time the constructor returns, every field with an
initialiser has a value — and every field without one is still `null` or `0`. Spring cannot inject
a field before the object exists, so a field-injected dependency is necessarily `null` inside the
constructor. That is not a Spring limitation; it is the language.

*Reflection can write private fields.* `Field.setAccessible(true)` lets Spring assign a private
field from outside the class. This is how field injection works at all, and it is why a
field-injected class cannot use `final` — `final` fields are fixed once the constructor completes.
Constructor injection can use `final`, which is most of the argument for it.

*Java has no destructors.* `finalize` is gone, and garbage collection is not deterministic, so
there is no language-level "this object is finished" hook. Anything that must release a resource
needs an explicit call — which is exactly what `@PreDestroy`, `DisposableBean` and a destroy-method
are. It also explains why a bean the container stops tracking never gets cleaned up: nothing else
is going to do it.

*An interface can be a callback.* `InitializingBean`, `DisposableBean` and the various `*Aware`
types are ordinary interfaces the container checks for with `instanceof`. There is no magic; the
container is just asking "do you implement this?" before calling you.

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

## What this changes for you

Now that the mechanism is in place, the short version — the things that are true and
surprise people:

| Assumption | Reality |
|---|---|
| Dependencies are available in the constructor | Only the **constructor-injected** ones |
| `@PostConstruct` runs on the finished bean | It runs **before** the proxy is created |
| Constructor injection is just a style preference | It is the only style that **cannot** form a cycle |
| A prototype is destroyed with the context | It is **never** destroyed. Spring forgets it at handover |
| A prototype injected into a singleton stays fresh | It is resolved **once**, then reused forever |

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

## Reading the source yourself

Everything named below is already on your machine, but as compiled jars. Unpack the sources once —
they land in `.spring-sources/`, which is gitignored:

```bash
./tools/fetch-spring-sources.sh spring-beans
```

Now you can grep them, which is faster than any IDE search:

```bash
grep -rn "class AbstractAutowireCapableBeanFactory" .spring-sources/
```

**Then walk the path below, in order.** It is not a list of classes to read in full. Each stop names
one method and one thing to notice, and that is all you need from it — most of these classes are
hundreds of lines you can safely ignore.

Do it once with a debugger rather than by reading. Set a breakpoint where the path says to start,
run the lab test for this note, and step through. One pass is worth more than an hour of reading,
because you see the real values.

New to this? [How to read Spring source](reading-the-source.md) is the general method — how to find
the entry point for any feature, and the five shapes it will turn out to be.

<!-- widget:path:bean-creation -->

**You have understood this when you can say, without looking:** which of the three phases of
`doCreateBean` each callback you use hangs off.

---

## The classes involved

For reference later. The ordered walk is above; this is the same material as a lookup table.

`spring-beans`, almost entirely.

| Class | Role |
|---|---|
| `support.AbstractAutowireCapableBeanFactory` | `doCreateBean` and the three phases |
| `support.DefaultSingletonBeanRegistry` | the singleton caches and early exposure |
| `annotation.AutowiredAnnotationBeanPostProcessor` | `@Autowired` metadata and injection |
| `annotation.InitDestroyAnnotationBeanPostProcessor` | `@PostConstruct` and `@PreDestroy` |
| `support.DisposableBeanAdapter` | shutdown, in mirror order |
| `context.support.ApplicationContextAwareProcessor` | the context-level `Aware` callbacks |
