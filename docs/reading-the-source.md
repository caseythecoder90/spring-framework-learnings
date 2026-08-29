# How to read Spring source

Spring is about a million lines of Java. Opening a class and reading from the top is the worst
available strategy, and it is the one everyone tries first. This is the method that works instead.

It is also the method every note in this repo was written with, so if a note seems to know something
suspiciously specific, this is how.

---

## 1. Start from a stack, never from a class

Do not go looking for where transactions are implemented. Put a breakpoint on the first line of your
own `@Transactional` method, run a test that calls it, and look at the frames underneath yours.

That stack **is** the implementation, already filtered down to the ten frames that matter and
already in the right order. You did not have to know a single class name to get it.

Everything else in this guide is a way of getting a stack when you cannot easily get one.

---

## 2. Find the entry point by following the annotation

Nearly every Spring feature is switched on by an annotation, and that annotation is almost always a
thin shell. Open it and look for `@Import`:

```
@EnableScheduling
  └─ @Import(SchedulingConfiguration.class)
       └─ @Bean ScheduledAnnotationBeanPostProcessor
```

Three hops from an annotation you already use to the class that does the work. `@EnableAsync`,
`@EnableCaching`, `@EnableTransactionManagement` and `@EnableWebSecurity` are all the same shape.

When there is no annotation, look in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
inside the Boot jar. That file is the complete list of everything Boot might configure, and it is
searchable.

---

## 3. Recognise which of the five shapes you are looking at

This is the part that turns reading Spring from a slog into pattern matching. Almost everything is
one of these:

**A `BeanPostProcessor` that rewrites your beans.** It sees every bean as it is created and either
wraps it in a proxy or reads its annotations. `ScheduledAnnotationBeanPostProcessor`,
`AutowiredAnnotationBeanPostProcessor`, `AsyncAnnotationBeanPostProcessor`. If a feature involves an
annotation on a method of your class, look for one of these. Check `getOrder()` — ordering against
the auto-proxy creator decides whether the processor sees your object or a proxy of it.

**An `@Import` plus a registrar that adds bean definitions.** Used when a feature needs to *add*
beans rather than modify them. `@EnableConfigurationProperties`, `@MapperScan`, most of Boot.

**An `Aware` callback or a resolvable dependency.** How infrastructure gets handed to you.
`ApplicationEventPublisherAware`, `BeanFactoryAware`, and the `registerResolvableDependency` calls
in `AbstractApplicationContext.prepareBeanFactory` that let you inject things that have no bean
definition at all.

**A template method with a strategy.** An abstract base class holds the algorithm, a subclass or an
injected strategy fills in the specifics. `AbstractPlatformTransactionManager` is the algorithm;
`DataSourceTransactionManager` and `JpaTransactionManager` are the specifics. Read the abstract class
and you have understood every implementation at once. `JdbcTemplate` with its callback interfaces is
the same idea inverted.

**An AOP interceptor chain.** A list of advice, each calling `proceed()` on the next.
`TransactionInterceptor`, `CacheInterceptor`, `RetryOperationsInterceptor`, method security. If a
feature is "put an annotation on a method and behaviour changes", it is one of these, and every one
of them is bypassed by self-invocation for exactly the same reason.

Once you can name the shape, you know where to put the breakpoint before you have read anything.

---

## 4. The class named after the feature is usually not where the work is

`TransactionInterceptor.invoke` is four lines that delegate to `TransactionAspectSupport`. The
useful reading is nearly always one level up the inheritance chain, in the `Abstract*` or `*Support`
class. When a class looks disappointingly empty, go to its parent.

---

## 5. Read Spring's tests as the specification

`spring-tx/src/test/java/.../TransactionInterceptorTests.java` is a more precise account of what
`@Transactional` does than any prose, including this repo's. The tests also show which edge cases
the authors thought were worth worrying about, which is information you cannot get any other way.

When behaviour surprises you, search the Spring test sources for the method name before searching
the internet.

---

## 6. Practical tooling

**Get the sources locally** so you can grep them, which is faster than any IDE search:

```bash
./tools/fetch-spring-sources.sh
grep -rn "class TaskSchedulerRouter" .spring-sources/
```

**Turn on the right logger, not all of them.** `logging.level.org.springframework=TRACE` is
unreadable. `logging.level.org.springframework.transaction=TRACE` prints every begin, suspend,
commit and rollback with the transaction name, and is often enough on its own.

**Use conditional breakpoints in framework code.** A breakpoint in `ReflectiveMethodInvocation.proceed`
fires thousands of times. Condition it on
`method.getName().equals("placeOrder")` and it fires when you care.

**Write the throwaway test.** Every claim in this repo started as a five-line test in a lab module
rather than a guess. It is faster than reasoning, and unlike reasoning it tells you when you are
wrong.

---

## A worked example

This is the path for the most-asked-about feature in Spring. Every class and method below is
resolved against the real Spring jars by `CodePathsAreRealTest`, so if Spring renames one of these
the build fails rather than this page quietly misleading you.

<!-- widget:path:transactional-call -->

Two things in there are worth the trip on their own. `invokeWithinTransaction` is the entire feature
in one readable method. And `handleExistingTransaction` is a single switch statement that contains
the whole propagation matrix — the thing people memorise from tables and still get wrong.

---

## Adding a path of your own

Paths live in [`paths/`](../paths) as JSON, one file per trace:

```json
{
  "title": "What happens when ...",
  "summary": "One sentence on what the reader will understand afterwards.",
  "entry": "Where to put the first breakpoint.",
  "note": "the-note-this-belongs-to",
  "steps": [
    { "module": "spring-tx", "class": "org.some.Class", "method": "doThing", "notice": "What to look at." }
  ]
}
```

Add the file, run `mvn -pl labs/lab-codepaths test`, and the class and method names are checked for
you. Reference it from a note with an HTML comment, which GitHub renders as nothing and the site
turns into the stepper above:

```
<!-- widget:path:your-path-id -->
```

The rule for `notice`: write what you would tell someone sitting next to you, not what the method
does. "Read all four branches" is useful. "Publishes the event" is not.
