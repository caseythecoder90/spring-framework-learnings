# <Feature>, end to end

Verified against **Spring Framework <x.y.z> / Spring Boot <x.y.z>**. Every claim below is pinned by
a test in [`labs/lab-<feature>`](../labs/lab-<feature>); if a claim and a test ever disagree, the
test is right.

One or two sentences on where this sits: which track, and which other note it is closest to.

---

## How to work through this note

An explicit path. The tests are the worked examples, and without this section they stay buried
behind the prose.

1. **Read "Before this note".** One line on why.
2. **Run `<TheMostFoundationalTest>` and read it.**
   ```bash
   ./mvnw -pl labs/lab-<feature> -am test -Dtest=<TheMostFoundationalTest>
   ```
   One line on what it establishes.
3. **Read "<the mechanism section>".**
4. **Run and read `<TheTestWithTheRealBug>`**, then the matching section. Say why this one matters.
5. **Run and read the rest**, in the order the sections appear.
6. **Finish with "What this changes for you."**

---

## What you will be able to answer afterwards

Three or four concrete questions, phrased the way someone would actually ask them — ideally the
questions that sent them to this note in the first place.

- Why does ...?
- What happens when ...?
- When does ... silently do nothing?

---

## Before this note

**Which earlier note to read first**, and one sentence on why it is load-bearing here.

**The Java you need:**

Not a Java tutorial — the reader is a professional. This is the specific corner of Java the feature
sits in, which is usually one most people have never had to open. Two to four short subsections,
each with a small code sample where it helps.

Where a claim here can be tested, test it. Plain-Java baseline tests belong in the lab alongside the
Spring ones; see `JavaAnnotationBaselineTest` in `lab-annotations`.

*A named idea.* What it is, and the one consequence that matters downstream.

*Another named idea.* Same.

---

## <The mechanism>

The wiring chain, with real class names:

```
@EnableX
  └─ @Import(XConfiguration.class)
       └─ @Bean(name = "...internalXProcessor")
            XBeanPostProcessor
```

- What runs at bean-definition time?
- What runs at bean-creation time?
- What runs at context-refresh time?
- What runs on first use?

Timing is usually where the surprises are.

→ `XWiringTest`

---

## <The default that bites>

Every Spring feature has one. Name it, show the property or the field, say what it costs.

```yaml
spring:
  x:
    something: <the default>
```

→ `XDefaultsTest`

---

## Failure modes

What happens when it throws, when it is called from the wrong thread, when there are two of them,
when the context is still starting, when there are three instances of the application.

→ `XFailureTest`

---

## What this changes for you

The surprises table, here rather than at the top, where the reader now has the mental model to make
sense of it. If this table is boring, the note is not worth writing.

| Assumption | Reality |
|---|---|
| | |

---

## Review checklist

Questions to ask of any use of this feature in a code review.

- [ ]
- [ ]

---

## The code path

<!-- widget:path:<feature>-<action> -->

---

## Source map

| Class | Role |
|---|---|
| | |
