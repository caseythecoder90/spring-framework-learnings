# The annotation model, end to end

Verified against **Spring Framework 7.0.9 / Spring Boot 4.1.1**. Every claim below is pinned by a
test in [`labs/lab-annotations`](../labs/lab-annotations); if a claim and a test ever disagree, the
test is right.

This is the first note in the Foundations track because it explains the others. `@Transactional`,
`@Scheduled`, `@Cacheable` and every stereotype you have ever used are found by the machinery
described here.

---

## How to work through this note

Reading it top to bottom works, but this order is faster:

1. **Read "Before this note" below.** It is the corner of Java that annotations live in — not
   difficult, but most professional Java work never opens it. Skip a subsection if you can already
   state the answer without checking. About ten minutes.
2. **Run the Java baseline tests and read them.**
   ```bash
   ./mvnw -pl labs/lab-annotations -am test -Dtest=JavaAnnotationBaselineTest
   ```
   Six tests, no Spring in any of them. They prove every claim in step 1, and reading a passing
   assertion is faster than trusting a paragraph.
3. **Read "The problem Spring has"**, which is one concrete scenario, then work through the
   mechanism sections in order. Each ends with a `→ TestName` pointer.
4. **Run and read those tests as you go.** They are the worked examples; the prose is commentary on
   them. `MetaAnnotationTest` first, then `AliasForTest`, then `SearchStrategyTest`.
5. **Finish with "What this changes for you."** It will not land before this point, which is why it
   is at the end rather than the top.
6. **Optional, and worth it once:** follow [the code path](#the-code-path) in your IDE with a
   breakpoint in `AnnotationTypeMapping.getMirrorSets`.

Done in that order this note is self-contained. You should not need to look anything up.

---

## What you will be able to answer afterwards

- Why does `@RestController` work everywhere `@Component` is expected, when the class says neither?
- Why does my custom annotation do nothing at all, with no error?
- When does `@Transactional` on an interface method get found, and when is it silently ignored?
- What is the difference between `AnnotationUtils.getAnnotation` and `findAnnotation`, and why does
  choosing wrong return `null` instead of failing?

---

## Before this note

**This is the first note in the track**, so nothing precedes it. Read it before
[the proxy model](proxies.md), which assumes Spring can already find an annotation.

**The Java you need.** Five facts, all plain Java, all pinned by `JavaAnnotationBaselineTest`.

### An annotation is an interface

`@interface Marker {}` compiles to an interface extending `java.lang.annotation.Annotation`. Its
attributes are **abstract methods**, which is why they are written with `()` and why defaults are
declared with `default`:

```java
@interface Marker {
    String value() default "";     // a method, not a field
}
```

That matters later: `@AliasFor` talks about attributes as methods because that is what they are.

### Retention decides whether it exists at runtime at all

```java
RetentionPolicy.SOURCE    // discarded by the compiler        (@Override)
RetentionPolicy.CLASS     // in the class file, never loaded  ← THE DEFAULT
RetentionPolicy.RUNTIME   // loaded, visible to reflection
```

**The default is `CLASS`.** An annotation declared without `@Retention(RUNTIME)` is completely
invisible to reflection, and therefore to Spring. Nothing warns you — the annotation simply does
nothing. When a custom annotation "isn't working", check this first.

### Reflection returns only what is written on the element

This is the gap everything else exists to close:

```java
@Stereotype                        // and @Stereotype is itself annotated @Marker
class Stereotyped { }

Stereotyped.class.getAnnotation(Stereotype.class);   // found
Stereotyped.class.getAnnotation(Marker.class);       // null
```

Java has no concept of one annotation implying another. It never walks the graph.

### `@Inherited` is much narrower than it sounds

It applies to **classes only**, and only up the **superclass** chain:

| Situation | Inherited? |
|---|---|
| subclass of an annotated class, annotation is `@Inherited` | yes |
| subclass of an annotated class, no `@Inherited` | no |
| class implementing an annotated interface | **never**, `@Inherited` or not |
| method overriding an annotated method | **never** |

Also worth knowing: an inherited annotation is *not* declared on the subclass.
`getAnnotation` finds it, `getDeclaredAnnotation` does not. Spring spells that same distinction
`isPresent` versus `isDirectlyPresent`.

### The annotation instance is already a proxy

Before Spring is anywhere near it. There is no concrete class implementing your annotation
interface, so the JVM generates a JDK dynamic proxy backed by the values in the class file:

```java
Marker marker = Annotated.class.getAnnotation(Marker.class);
Proxy.isProxyClass(marker.getClass());   // true
marker.getClass() == Marker.class;       // false
marker.annotationType() == Marker.class; // true  ← the accessor that tells the truth
```

So Spring's `synthesize()` is not doing anything exotic. It substitutes *its own* proxy, carrying
merged and aliased values, for the one the JVM would have made.

→ `JavaAnnotationBaselineTest`

---

## The problem Spring has

Spring's whole programming model depends on exactly the thing Java refuses to do.

```java
@RestController
class OrderController { }
```

`@RestController` is annotated `@Controller`, which is annotated `@Component`. Component scanning
needs to find `@Component` here. Plain reflection returns `null` for it, twice over.

On top of that, Spring needs attribute *overriding*: `@GetMapping("/orders")` has to set
`@RequestMapping`'s `path`, even though those are different annotations.

So `spring-core` contains a small graph engine that treats annotations as nodes and
meta-annotations as edges, and reads attributes through a mapping table. The rest of this note is
that engine.

---

## The model: distance, presence, absence

```java
MergedAnnotations annotations = MergedAnnotations.from(OrderEndpoint.class);
annotations.get(Component.class).getDistance();   // 2
```

**Distance** is how many meta-annotation hops away the annotation was found. Zero means written on
the element itself; two means it took two edges to get there.

**Presence** comes in three flavours, and mixing them up is a common bug:

| Call | True when |
|---|---|
| `isDirectlyPresent` | written on the element itself |
| `isMetaPresent` | reached through another annotation |
| `isPresent` | either of the above |

**Absence is an object, not `null`.** `get()` always returns a `MergedAnnotation`, so Spring's own
code chains calls without null checks. But absent is not the same as empty — reading an attribute
off an absent annotation throws `NoSuchElementException`. Use `getValue(name, type)`, which returns
an `Optional`, when you are not sure.

→ `MetaAnnotationTest`

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

Compare that table with the `@Inherited` one above and the design becomes obvious: each strategy
buys back one of the things plain Java refuses to do.

- `SUPERCLASS` will find an annotation the JVM considers non-inheritable. Spring simply ignores the
  flag.
- An annotation on an **interface method** is only found by `TYPE_HIERARCHY`. This is why
  `@Transactional` on an interface method works with proxying, while a naive `getAnnotation` on the
  implementing method sees nothing.

> Framework 7 note: the old `TYPE_HIERARCHY_AND_ENCLOSING_CLASSES` strategy is gone, with no
> drop-in replacement, if you are porting code from Boot 3.x.

→ `SearchStrategyTest`

---

## `get*` versus `find*`, the asymmetry worth memorising

`AnnotationUtils` offers a `get` and a `find` family. They look interchangeable and are not:

| | Strategy | Depth limit |
|---|---|---|
| `getAnnotation` | `INHERITED_ANNOTATIONS` | **one meta-level** (`isSingleLevelPresent`) |
| `findAnnotation` | `TYPE_HIERARCHY` | any depth |

On a class annotated `@ServiceEndpoint`, meta-annotated `@Service`, meta-annotated `@Component`:

```java
AnnotationUtils.getAnnotation(OrderEndpoint.class, Service.class);    // found, one hop
AnnotationUtils.getAnnotation(OrderEndpoint.class, Component.class);  // null, two hops
AnnotationUtils.findAnnotation(OrderEndpoint.class, Component.class); // found
```

That `null` is silent. The same split exists as `isAnnotated` versus `hasAnnotation` on
`AnnotatedElementUtils`.

→ `MetaAnnotationTest`, `SearchStrategyTest`

---

## Synthesized annotations

`synthesize()` returns a proxy carrying the merged, alias-resolved values — replacing the JVM's own
proxy from "Before this note".

- `annotationType()` is correct, and `equals` against a real annotation instance still works.
- `getClass()` is a proxy class, so never switch on it.
- Synthesizing costs something, which is why Spring's hot paths use `MergedAnnotation` accessors
  directly and only synthesize when handing an annotation to user code.

---

## Why it is fast enough to run constantly

Annotation lookup happens for every bean at startup, so the engine is aggressively optimised:

- `AnnotationTypeMappings` are cached per annotation type. The graph for `@GetMapping` is computed
  once per JVM.
- `AnnotationFilter.PLAIN` short-circuits `java.lang` and `jakarta` annotations, which can never be
  meta-annotated with anything Spring cares about.
- `AnnotationsScanner` has hand-written fast paths for elements carrying plain Java annotations
  only.

If you ever see annotation scanning in a profile, it is usually a custom `TYPE_HIERARCHY` search
running per request instead of once at startup.

---

## What this changes for you

Now that the mechanism is in place, here is the short version — the things that are true and
surprise people:

| Assumption | Reality |
|---|---|
| `getAnnotation` finds meta-annotations | Java's does not at all; Spring's stops **one level out** |
| `findAnnotation` is the same thing | It searches the whole type hierarchy, at any depth |
| `@Inherited` means "subclasses get it" | Only for **classes**. Never interfaces, never methods |
| A custom annotation just works | Not without `@Retention(RUNTIME)`. It fails silently |
| The annotation you receive is the one you wrote | It is a proxy, in plain Java and in Spring |
| A missing annotation returns `null` | It returns an absent `MergedAnnotation`, and reading an attribute off it throws |

---

## Review checklist

- [ ] Does every custom annotation declare `@Retention(RUNTIME)`?
- [ ] `get*` or `find*` — does the call reach as far as the annotation actually is?
- [ ] Is the annotation on an interface or an interface method? Then you need `TYPE_HIERARCHY`.
- [ ] Custom stereotype: is anything reading it more than one hop away?
- [ ] `@AliasFor` pairs: same default, and both directions declared?
- [ ] Any code doing `annotation.getClass()` rather than `annotationType()`?
- [ ] Any `TYPE_HIERARCHY` search on a request path rather than at startup?

---

## Reading the source yourself

Everything named below is already on your machine, but as compiled jars. Unpack the sources once —
they land in `.spring-sources/`, which is gitignored:

```bash
./tools/fetch-spring-sources.sh spring-core
```

Now you can grep them, which is faster than any IDE search:

```bash
grep -rn "class MergedAnnotations" .spring-sources/
```

**Then walk the path below, in order.** It is not a list of classes to read in full. Each stop names
one method and one thing to notice, and that is all you need from it — most of these classes are
hundreds of lines you can safely ignore.

Do it once with a debugger rather than by reading. Set a breakpoint where the path says to start,
run the lab test for this note, and step through. One pass is worth more than an hour of reading,
because you see the real values.

New to this? [How to read Spring source](reading-the-source.md) is the general method — how to find
the entry point for any feature, and the five shapes it will turn out to be.

<!-- widget:path:annotation-lookup -->

**You have understood this when you can say, without looking:** why `@RestController` resolves to
`@Component`, and which single method decides it.

---

## The classes involved

For reference later. The ordered walk is above; this is the same material as a lookup table.

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
