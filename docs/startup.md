# Container startup, end to end

Verified against **Spring Framework 7.0.9 / Spring Boot 4.1.1**. Every claim below is pinned by a
test in [`labs/lab-startup`](../labs/lab-startup); if a claim and a test ever disagree, the test is
right.

[Bean lifecycle](bean-lifecycle.md) is what happens to **one bean**. This note is what happens to
**the container**, and the order the two interleave in — which is where the expensive bugs are.

---

## The short version

| Assumption | Reality |
|---|---|
| `ContextRefreshedEvent` means "startup is beginning" | It is the **last** thing `refresh()` does. Every `SmartLifecycle` has already started |
| A `SmartLifecycle` with no `getPhase()` starts early | Default phase is `Integer.MAX_VALUE`, so it starts **last** and stops **first** |
| A `Lifecycle` bean gets started by the container | Only `SmartLifecycle` beans with `isAutoStartup()`. A plain `Lifecycle` never starts on its own |
| A bean is a bean whenever it is created | A bean created too early misses every `BeanPostProcessor`, so it is **never proxied** |
| `CommandLineRunner` is part of container startup | It runs after `refresh()` has completely finished, and in a web app after the port is open |

---

## The order

`AbstractApplicationContext.refresh()` is twelve numbered calls in one method. Here is what they
look like from the outside, with one bean of every kind that gets a say:

```
BeanDefinitionRegistryPostProcessor.postProcessBeanDefinitionRegistry   definitions can still be added
BeanDefinitionRegistryPostProcessor.postProcessBeanFactory
BeanFactoryPostProcessor.postProcessBeanFactory                         definitions can still be edited
BeanPostProcessor  <constructed>                                        ← the line that matters
  bean <constructed>
  bean @PostConstruct
  BeanPostProcessor.postProcessAfterInitialization
SmartInitializingSingleton.afterSingletonsInstantiated                  everything exists
SmartLifecycle.start
ContextRefreshedEvent                                                   refresh() returns

ContextClosedEvent                                                      close()
SmartLifecycle.stop
bean @PreDestroy
```

Two things fall out of that listing and are worth holding on to.

**Post-processors run in two waves, and the second wave has not been built when the first runs.**
`BeanFactoryPostProcessor`s edit *definitions*; `BeanPostProcessor`s edit *instances*; and every one
of the former runs before any of the latter exists.

**`ContextRefreshedEvent` is a finishing line, not a starting gun.** If you need to run something
before beans start, that is `SmartInitializingSingleton` or a low-phase `SmartLifecycle`, not a
`ContextRefreshedEvent` listener.

→ `RefreshOrderTest`

<!-- widget:path:context-refresh -->

---

## Instantiated too early

This is the startup bug worth being able to recognise on sight.

```java
@Bean
BeanFactoryPostProcessor auditor(OrderService orders) {   // <- not static, and it injects
    return beanFactory -> { ... };
}
```

To call that method the container must build the `@Configuration` class, which means building
`OrderService`, which happens during the `BeanFactoryPostProcessor` phase — **before any
`BeanPostProcessor` is registered**. Missing the `BeanPostProcessor`s means missing
`AnnotationAwareAspectJAutoProxyCreator`, which means `OrderService` is never proxied, which means
its `@Transactional`, `@Async`, `@Cacheable` and `@Retryable` annotations do nothing at all.

The only trace is one INFO line, which nobody reads:

```
Bean 'orderService' of type [...] is not eligible for getting processed by all
BeanPostProcessors (for example: not eligible for auto-proxying)
```

The rule that avoids it: **`@Bean` methods returning a `BeanFactoryPostProcessor` or a
`BeanPostProcessor` must be `static`**, and must not inject anything. A static method needs no
instance of the configuration class, so nothing is dragged up with it.

The same trap has other doors: an `@Autowired` field on a `BeanPostProcessor`, a
`PropertySourcesPlaceholderConfigurer` declared non-static, a `BeanFactoryPostProcessor` that calls
`getBean` to look at an instance instead of `getBeanDefinition` to look at a definition.

→ `EagerInstantiationTest`

---

## `SmartLifecycle`

The hook for things that are not beans: a Kafka consumer, a WebSocket, a background loop, anything
with a start and a stop that should line up with the container's.

```java
@Component
class MessageConsumer implements SmartLifecycle {

    @Override public int getPhase() { return 0; }        // say it explicitly. Always.
    @Override public void start() { ... }
    @Override public void stop()  { ... }
    @Override public boolean isRunning() { ... }
}
```

- **Low phases start first and stop last.** Shutdown is the exact mirror of startup, so whatever
  your consumer depends on is still running while the consumer is stopping.
- **The default phase is `Integer.MAX_VALUE`**, not `0`. Leave `getPhase()` alone and your bean
  starts after everything else and stops before everything else — which for a consumer is usually
  right, and for infrastructure is usually wrong.
- **`isAutoStartup()` defaults to `true`** on `SmartLifecycle`, and a plain `Lifecycle` bean is
  never started automatically at all. `DefaultLifecycleProcessor.onRefresh` only looks at
  `SmartLifecycle` beans that opt in.
- **`stop(Runnable)` is the method the container calls**, not `stop()`. Override it and forget to
  run the callback and every shutdown blocks for
  `spring.lifecycle.timeout-per-shutdown-phase` — thirty seconds by default, per phase.

→ `LifecyclePhasesTest`

---

## Where Boot joins in

`SpringApplication.run` refreshes the context and then keeps going, so Boot's phases sit *outside*
`refresh()` entirely:

```
ApplicationStartingEvent
ApplicationEnvironmentPreparedEvent      the Environment exists; no beans do
ApplicationContextInitializedEvent
ApplicationPreparedEvent                 definitions loaded, nothing instantiated
    ---- refresh() ----                  everything in "The order" above
ContextRefreshedEvent
ApplicationStartedEvent                  in a web app, the port is now open
ApplicationRunner / CommandLineRunner
ApplicationReadyEvent
```

Two consequences that come up constantly:

- **A `CommandLineRunner` is not startup code.** By the time it runs the application is already
  serving traffic. Work that must finish *before* the first request belongs in a
  `SmartLifecycle`, or in `@PostConstruct` if it is per-bean.
- **A runner that throws stops the application.** `SpringApplication.callRunners` lets the
  exception out and closes the context, so `ApplicationReadyEvent` never fires. That makes a runner
  a reasonable place to fail fast on a bad configuration — and a bad place for anything optional.

For anything that needs the `Environment` but must run before beans exist, the hook is
`ApplicationEnvironmentPreparedEvent`, registered in `spring.factories` rather than as a bean —
because at that point there is no context to hold one.

→ `BootStartupTest`

---

## Review checklist

- [ ] Is any `@Bean` method returning a `BeanFactoryPostProcessor` or `BeanPostProcessor` non-static,
      or injecting anything?
- [ ] Does the startup log contain "not eligible for getting processed by all BeanPostProcessors"?
      If so, is that bean supposed to be proxied?
- [ ] Does any `SmartLifecycle` rely on the default phase without saying so?
- [ ] Does any `stop(Runnable)` override always run its callback, including on the failure path?
- [ ] Is work in a `CommandLineRunner` that should have finished before the first request arrives?
- [ ] Is a `ContextRefreshedEvent` listener being used for something that needs to happen *before*
      the beans start?
- [ ] In a test, does the same context get reused? See [testing](testing.md) — `ContextRefreshedEvent`
      fires once per context, not once per test.

---

## Source map

| Class | Role |
|---|---|
| `context.support.AbstractApplicationContext` | `refresh()`, `finishRefresh()`, `doClose()` — the twelve steps |
| `context.support.PostProcessorRegistrationDelegate` | the three-pass post-processor ordering |
| `context.annotation.ConfigurationClassPostProcessor` | `@Configuration`, `@Import`, `@ComponentScan`, `@Conditional` |
| `beans.factory.support.DefaultListableBeanFactory` | `preInstantiateSingletons` |
| `context.support.DefaultLifecycleProcessor` | phases, `isAutoStartup`, and the shutdown timeout |
| `boot.SpringApplication` | `run()`, `callRunners()`, and everything outside `refresh()` |
