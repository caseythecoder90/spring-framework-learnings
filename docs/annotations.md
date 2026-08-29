# The annotation model, end to end

Verified against **Spring Framework 7.0.9 / Spring Boot 4.1.1**. Every claim below is pinned by a
test in [`labs/lab-annotations`](../labs/lab-annotations); if a claim and a test ever disagree, the
test is right.

This is the first note in the Foundations track because it explains the others. `@Transactional`,
`@Scheduled`, `@Cacheable` and every stereotype you have ever used are found by the machinery
described here.

---

## The short version

| Assumption | Reality |
|---|---|
| `getAnnotation` finds meta-annotations | Java's does not at all; Spring's stops **one level out** |
| `findAnnotation` is the same thing | It searches the whole type hierarchy, at any depth |
| `@Inherited` means "subclasses get it" | Only for **classes**. Never interfaces, never methods |
| The annotation you receive is the one you wrote | It is usually a **synthesized proxy** |
| A missing annotation returns null | It returns an absent `MergedAnnotation`, and reading an attribute off it throws |

---

## Why Spring needs its own annotation engine

Java's reflection is deliberately shallow. Ask a `@Service`-annotated class for `@Component` and you
get null, because Java has no concept of one annotation implying another. There is no attribute
overriding, no aliasing, and `@Inherited` covers only the superclass-of-a-class case.

Spring's whole programming model depends on the opposite. So `spring-core` contains a small graph
engine that treats annotations as nodes and meta-annotations as edges.

→ `MetaAnnotationTest`

---

## The model: distance, presence, absence

```java
MergedAnnotations annotations = MergedAnnotations.from(OrderEndpoint.class);
annotations.get(Component.class).getDistance();   // 2
```

**Distance** is how many meta-annotation hops away the annotation was found. Zero means written on
the element itself.

**Presence** comes in three flavours, and mixing them up is a common bug:

| Call | True when |
|---|---|
| `isDirectlyPresent` | written on the element itself |
| `isMetaPresent` | reached through another annotation |
| `isPresent` | either of the above |

**Absence is an object, not null.** `get()` always returns a `MergedAnnotation`, so Spring's own
code chains calls without null checks. But absent is not the same as empty — reading an attribute
off an absent annotation throws `NoSuchElementException`. Use `getValue(name, type)`, which returns
an `Optional`, when you are not sure.

---

## `@AliasFor` does two different jobs

**Within one annotation**, it makes two attributes interchangeable:

```java
@AliasFor("path") String value() default "";
@AliasFor("value") String path() default "";
```

That is why `@RequestMapping("/x")` and `@RequestMapping(path = "/x")` are the same thing. Set both
to *different* values and you get an `AnnotationConfigurationException` — at read time, not compile
time, which is why it usually surfaces as a confusing startup failure.

**Pointing at a meta-annotation**, it overrides an attribute further up the graph:

```java
@Endpoint
@interface ReadOnlyEndpoint {
    @AliasFor(annotation = Endpoint.class, attribute = "path")
    String route() default "";
}
```

This is exactly the `@GetMapping` to `@RequestMapping` relationship. Internally both jobs are the
same mechanism: a *mirror set* of attributes that must agree, resolved in `AnnotationTypeMapping`.

→ `AliasForTest`

---

## Search strategies

Four of them, and the choice decides whether your annotation is found at all.

| Strategy | Looks at |
|---|---|
| `DIRECT` | the element only |
| `INHERITED_ANNOTATIONS` | the element, plus superclasses **if** the annotation is `@Inherited` |
| `SUPERCLASS` | the element and its superclasses, **ignoring** `@Inherited` |
| `TYPE_HIERARCHY` | superclasses **and interfaces**, including method overrides |

The important gaps, all of which have a test:

- Java's `@Inherited` never crosses an interface, and never applies to methods.
- `SUPERCLASS` will find an annotation the JVM considers non-inheritable. Spring simply ignores the
  flag.
- An annotation on an **interface method** is only found by `TYPE_HIERARCHY`. This is why
  `@Transactional` on an interface method works with proxying but a naive `getAnnotation` on the
  implementing method sees nothing.

> Framework 7 note: the old `TYPE_HIERARCHY_AND_ENCLOSING_CLASSES` strategy is gone. If you are
> porting code from Boot 3.x that used it, there is no drop-in replacement.

→ `SearchStrategyTest`

---

## `get*` versus `find*`, the asymmetry worth memorising

`AnnotatedElementUtils` and `AnnotationUtils` both offer a `get` and a `find` family. They look
interchangeable and are not:

| | Strategy | Depth limit |
|---|---|---|
| `AnnotationUtils.getAnnotation` | `INHERITED_ANNOTATIONS` | **one meta-level** (`isSingleLevelPresent`) |
| `AnnotationUtils.findAnnotation` | `TYPE_HIERARCHY` | any depth |

So on a class annotated `@ServiceEndpoint`, which is meta-annotated `@Service`, which is
meta-annotated `@Component`:

```java
AnnotationUtils.getAnnotation(OrderEndpoint.class, Service.class);    // found, one hop
AnnotationUtils.getAnnotation(OrderEndpoint.class, Component.class);  // null, two hops
AnnotationUtils.findAnnotation(OrderEndpoint.class, Component.class); // found
```

That null is silent. When a custom stereotype "does not work", this is the first thing to check.
The same split exists as `isAnnotated` versus `hasAnnotation` on `AnnotatedElementUtils`.

→ `MetaAnnotationTest`, `SearchStrategyTest`

---

## Synthesized annotations are proxies

`synthesize()` returns a JDK dynamic proxy implementing the annotation interface, not the instance
the compiler produced. That is how merged and aliased values reach code that just calls
`annotation.value()`.

Consequences worth knowing:

- `annotationType()` is correct, and `equals` against a real annotation instance still works.
- `getClass()` is *not* the annotation type; it is a proxy class. Never switch on it.
- Synthesizing costs something, which is why Spring's hot paths use `MergedAnnotation` accessors
  directly and only synthesize when handing an annotation to user code.

---

## Why it is fast enough to run constantly

Annotation lookup happens for every bean at startup, so the engine is aggressively optimised:

- `AnnotationTypeMappings` are cached per annotation type. The graph for `@GetMapping` is computed
  once per JVM.
- `AnnotationFilter.PLAIN` short-circuits `java.lang` and `jakarta` annotations, which can never be
  meta-annotated with anything Spring cares about.
- `AnnotationsScanner` has hand-written fast paths for elements with plain Java annotations only.

If you ever see annotation scanning in a profile, it is usually a custom `TYPE_HIERARCHY` search
being run per request instead of once at startup.

---

## Review checklist

- [ ] `get*` or `find*` — does the call reach as far as the annotation actually is?
- [ ] Is the annotation on an interface or an interface method? Then you need `TYPE_HIERARCHY`.
- [ ] Custom stereotype: is it meta-annotated, and is anything reading it more than one hop away?
- [ ] `@AliasFor` pairs: same default, and both directions declared?
- [ ] Any code doing `annotation.getClass()` on something that might be synthesized?
- [ ] Any `TYPE_HIERARCHY` search on a request path rather than at startup?

---

## The code path

<!-- widget:path:annotation-lookup -->

---

## Source map

All in `spring-core`, package `org.springframework.core.annotation`.

| Class | Role |
|---|---|
| `MergedAnnotations` / `MergedAnnotation` | the public API |
| `AnnotationsScanner` | decides *where* to look, per search strategy |
| `AnnotationTypeMappings` | builds and caches the meta-annotation graph |
| `AnnotationTypeMapping` | attribute mapping and `@AliasFor` mirror sets |
| `TypeMappedAnnotation` | reads an attribute through the mapping |
| `SynthesizedMergedAnnotationInvocationHandler` | the proxy behind `synthesize()` |
| `AnnotationUtils` / `AnnotatedElementUtils` | the older façades, with the `get`/`find` split |
