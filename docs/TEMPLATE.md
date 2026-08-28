# <Feature>, end to end

Verified against **Spring Framework <x.y.z> / Spring Boot <x.y.z>**. Every claim below is pinned by
a test in [`labs/lab-<feature>`](../labs/lab-<feature>); if a claim and a test ever disagree, the
test is right.

---

## The short version

Three to six rows. What you believed, and what is actually true. If this table is boring, the note
is not worth writing.

| Assumption | Reality |
|---|---|
| | |

---

## From annotation to behaviour

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

## <The mechanism>

How it works when nothing goes wrong. Prefer a short call chain or a diagram over prose.

→ `XTest`

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

## Review checklist

Questions to ask of any use of this feature in a code review.

- [ ]
- [ ]

---

## Source map

| Class | Role |
|---|---|
| | |
