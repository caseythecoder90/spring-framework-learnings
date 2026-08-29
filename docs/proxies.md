# The proxy model, end to end

Verified against **Spring Framework 7.0.9 / Spring Boot 4.1.1**. Every claim below is pinned by a
test in [`labs/lab-proxies`](../labs/lab-proxies); if a claim and a test ever disagree, the test is
right.

Second note in the Foundations track. Nearly every "put an annotation on a method and behaviour
changes" feature in Spring is an interceptor on a proxy, so the failure modes here are shared by
`@Transactional`, `@Cacheable`, `@Async`, `@Retryable` and method security all at once.

---

## The short version

| Assumption | Reality |
|---|---|
| The bean I injected is my class | It is usually a **subclass** of it, or an interface implementation |
| Calling my own method still applies the annotation | **No.** Self-invocation skips the proxy entirely |
| A proxy is my object with extra behaviour | It is a **different object** that delegates to yours |
| Fields on the injected bean are set | On a CGLIB proxy they are **null** — no constructor ran |
| `final` just means "do not override" | It also means **not advised**, silently |

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

## The code path

<!-- widget:path:proxy-creation -->

---

## Source map

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
