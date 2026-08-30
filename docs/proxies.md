# The proxy model, end to end

Verified against **Spring Framework 7.0.9 / Spring Boot 4.1.1**. Every claim below is pinned by a
test in [`labs/lab-proxies`](../labs/lab-proxies); if a claim and a test ever disagree, the test is
right.

Second note in the Foundations track. Nearly every "put an annotation on a method and behaviour
changes" feature in Spring is an interceptor on a proxy, so the failure modes here are shared by
`@Transactional`, `@Cacheable`, `@Async`, `@Retryable` and method security all at once.

---

## How to work through this note

1. **Read "Before this note".** The Java half is what a proxy actually *is*, and it is the piece
   that makes everything else here obvious rather than magical. Five minutes.
2. **Run `ProxyTypeSelectionTest` and read it.**
   ```bash
   ./mvnw -pl labs/lab-proxies test -Dtest=ProxyTypeSelectionTest
   ```
   Five tests showing which kind of proxy you get and how the two differ.
3. **Read "Which proxy you get" and "Who creates the proxy".**
4. **Run and read `SelfInvocationTest`, then `CglibLimitationsTest`.** These are the two that
   explain real bugs. Do not skip them; they are the reason this note exists.
5. **Read the matching sections**, then `AutoProxyCreatorTest` for the container-level view.
6. **Finish with "What this changes for you."**

---

## What you will be able to answer afterwards

- Why is the bean I injected not an instance of the class I wrote?
- Why does `@Transactional` do nothing when I call the method from another method of the same class?
- Why is a field on an injected bean `null` when the getter returns the right value?
- When do I get a JDK proxy and when a CGLIB one, and what breaks in each case?

---

## Before this note

**Read [the annotation model](annotations.md) first.** Spring has to *find* `@Transactional` before
it can proxy anything, and that is the previous note.

**The Java you need**, none of it Spring-specific:

*A proxy is a different object that forwards to yours.* Java gives you two ways to make one.

**JDK dynamic proxies** are built into the JDK. `Proxy.newProxyInstance` generates a class at
runtime implementing a list of **interfaces**, routing every call to an `InvocationHandler`:

```java
Foo proxy = (Foo) Proxy.newProxyInstance(loader, new Class[]{ Foo.class }, handler);
```

The catch is in that signature: interfaces only. The result implements `Foo` but is **not** an
instance of `FooImpl`, so casting to the implementation class throws `ClassCastException`.

**Subclass proxies** are the other approach, and the JDK has no built-in support, so Spring bundles
CGLIB (repackaged, in `spring-core`). It generates a **subclass** of your class at runtime and
overrides each method. That means the proxy passes `instanceof` for both the class and its
interfaces — but it inherits Java's rules about what can be overridden:

- a `final` method cannot be overridden, so it cannot be intercepted;
- a `private` method is not inherited at all;
- the class itself must not be `final`.

*One more piece.* Creating a subclass normally means running a constructor, which would run your
constructor's side effects twice. Spring avoids that with **Objenesis**, which allocates an
instance without invoking any constructor at all. Useful, and it has a consequence that catches
everyone: the proxy's own fields are never assigned. That is the whole of "What a proxy does not
carry over" below.

---

## Which proxy you get

`DefaultAopProxyFactory.createAopProxy` is about ten lines:

```java
if (optimize || proxyTargetClass || !hasUserSuppliedInterfaces()) {
    return (targetClass.isInterface() || Proxy.isProxyClass(targetClass))
        ? new JdkDynamicAopProxy(config)
        : new ObjenesisCglibAopProxy(config);
}
return new JdkDynamicAopProxy(config);
```

| Situation | Result |
|---|---|
| Implements an interface, `proxyTargetClass=false` | JDK dynamic proxy |
| `proxyTargetClass=true` | CGLIB subclass |
| No interfaces at all | CGLIB subclass |

The two fail differently. A **JDK proxy** implements the interface but is *not* an instance of your
class, so `(OrderServiceImpl) bean` throws `ClassCastException`. A **CGLIB proxy** is a subclass, so
both casts work, but it cannot intercept `final` or `private` methods.

**Boot forces CGLIB by default.** `spring.aop.proxy-target-class` defaults to true, which removes
the whole category of "expected the impl, got a proxy" failures at the cost of the `final`
restriction.

> Boot 4 note: `spring-boot-starter-aop` is no longer in the dependency-management BOM. `spring-aop`
> arrives transitively via `spring-context`; add AspectJ explicitly if you want `@Aspect` support.

→ `ProxyTypeSelectionTest`, `AutoProxyCreatorTest`

---

## Who creates the proxy

An **auto-proxy creator**, which is just a `BeanPostProcessor` that returns a different object from
`postProcessAfterInitialization`. That is the entire mechanism.

```
AbstractAutoProxyCreator.postProcessAfterInitialization
  └─ wrapIfNecessary            does any advisor match this bean?
       └─ createProxy
            └─ DefaultAopProxyFactory.createAopProxy    JDK or CGLIB
```

Once that clicks, ordering rules elsewhere in Spring stop looking arbitrary. A `BeanPostProcessor`
with **lower** precedence than the auto-proxy creator receives the proxy; one with **higher**
precedence receives the raw bean. That is exactly why
`ScheduledAnnotationBeanPostProcessor.getOrder()` returns `LOWEST_PRECEDENCE` — it needs the proxy
so that `@Scheduled` and `@Transactional` compose.

→ `AutoProxyCreatorTest`

---

## Self-invocation

The one that matters:

```java
public void placeTwo() {
    place();   // plain Java call on `this`. No proxy. No transaction. No cache. No retry.
    place();
}
```

Advice lives on the proxy. A call from one method of your bean to another goes directly to the
target object, so nothing intercepts it. `@Transactional` on `place()` does nothing when `place()`
is reached this way, and there is no warning at startup or at runtime.

Three ways out, in the order I would try them:

1. **Move the method to another bean.** The call then goes through a proxy naturally, and it is
   usually better design anyway.
2. **Inject the bean into itself.** Works, needs `@Lazy` to avoid a circular reference, and looks
   strange enough that people ask questions.
3. **`AopContext.currentProxy()`** with `exposeProxy = true`. A `ThreadLocal` holding the proxy.
   Explicit and ugly, and it throws `IllegalStateException` rather than returning null when
   `exposeProxy` is off — a good failure, since the alternative is silence.

→ `SelfInvocationTest`

---

## What a proxy does not carry over

A CGLIB proxy is instantiated by **Objenesis**, which allocates the object without running any
constructor. So:

- The proxy's own fields are **never assigned**. Reading `bean.someField` off an injected reference
  gives null, while `bean.getSomeField()` works, because the call is delegated to the target
  instance that *was* constructed.
- Constructor side effects do not happen twice, which is the reason Spring does it this way.
- A `final` method cannot be overridden, so it is not advised — and worse, it executes on the
  **proxy** instance with its empty state. A final getter returns null and nothing warns you.

This cost this repo an hour in the events lab: an `@Async` listener whose latch fields read as null
through the injected reference. The fix was to move the state into a separate bean.

→ `CglibLimitationsTest`

---

## What this changes for you

Now that the mechanism is in place, the short version — the things that are true and
surprise people:

| Assumption | Reality |
|---|---|
| The bean I injected is my class | It is usually a **subclass** of it, or an interface implementation |
| Calling my own method still applies the annotation | **No.** Self-invocation skips the proxy entirely |
| A proxy is my object with extra behaviour | It is a **different object** that delegates to yours |
| Fields on the injected bean are set | On a CGLIB proxy they are **null** — no constructor ran |
| `final` just means "do not override" | It also means **not advised**, silently |

---

## Review checklist

- [ ] Does any annotated method get called from inside the same class?
- [ ] Is the annotated method public and non-final? (CGLIB cannot advise otherwise.)
- [ ] Does anything read a **field** off an injected bean rather than calling a method?
- [ ] Any cast to the implementation class where a JDK proxy might be in play?
- [ ] Any code branching on `bean.getClass()`? It should use `AopProxyUtils.ultimateTargetClass`.
- [ ] A new `BeanPostProcessor`: does it need the proxy or the raw bean, and is its order right?
- [ ] `@Transactional` on an interface method with a JDK proxy — is the annotation actually found?
      (See [the annotation model](annotations.md): that needs a `TYPE_HIERARCHY` search.)

---

## Reading the source yourself

Everything named below is already on your machine, but as compiled jars. Unpack the sources once —
they land in `.spring-sources/`, which is gitignored:

```bash
./tools/fetch-spring-sources.sh spring-aop spring-context
```

Now you can grep them, which is faster than any IDE search:

```bash
grep -rn "class AbstractAutoProxyCreator" .spring-sources/
```

**Then walk the path below, in order.** It is not a list of classes to read in full. Each stop names
one method and one thing to notice, and that is all you need from it — most of these classes are
hundreds of lines you can safely ignore.

Do it once with a debugger rather than by reading. Set a breakpoint where the path says to start,
run the lab test for this note, and step through. One pass is worth more than an hour of reading,
because you see the real values.

New to this? [How to read Spring source](reading-the-source.md) is the general method — how to find
the entry point for any feature, and the five shapes it will turn out to be.

<!-- widget:path:proxy-creation -->

**You have understood this when you can say, without looking:** which line decides JDK versus CGLIB,
and why a self-call never reaches the interceptor.

---

## The classes involved

For reference later. The ordered walk is above; this is the same material as a lookup table.

`spring-aop`, plus one Boot class.

| Class | Role |
|---|---|
| `aop.framework.autoproxy.AbstractAutoProxyCreator` | the `BeanPostProcessor` that swaps the bean |
| `aop.framework.DefaultAopProxyFactory` | JDK versus CGLIB, in ten lines |
| `aop.framework.JdkDynamicAopProxy` | interface-based proxy |
| `aop.framework.CglibAopProxy` / `ObjenesisCglibAopProxy` | subclass-based proxy |
| `aop.framework.ReflectiveMethodInvocation` | the advisor chain and `proceed()` |
| `aop.framework.AopContext` | `currentProxy()`, the self-invocation escape hatch |
| `aop.framework.AopProxyUtils` | `ultimateTargetClass`, for when you need the real type |
| `boot.autoconfigure.aop.AopAutoConfiguration` | Boot's CGLIB-by-default |
