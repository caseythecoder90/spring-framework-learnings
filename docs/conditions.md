# Conditions and auto-configuration, end to end

Verified against **Spring Framework 7.0.9 / Spring Boot 4.1.1**. Every claim below is pinned by a
test in [`labs/lab-conditions`](../labs/lab-conditions); if a claim and a test ever disagree, the
test is right.

This is the note that turns Boot from magic into mechanism. Auto-configuration is a list of class
names in a text file, sorted, filtered, and gated by conditions you could have written yourself —
and once that is concrete, "why is that bean not there" stops being a mystery.

---

## The short version

| Assumption | Reality |
|---|---|
| Auto-configuration is a special mechanism | It is ordinary `@Configuration` classes listed in a file, imported last |
| `@ConditionalOnMissingBean` means "if the user did not define one" | It means "if nothing is registered **at the moment this is evaluated**" |
| So it works the same in my own code | It does not. Nothing sorts your `@Configuration` classes, so the answer depends on registration order |
| A condition looks at beans | It looks at bean **definitions**. Nothing is instantiated while conditions run |
| `@ConditionalOnClass` on a missing class breaks startup | The annotation is read from bytecode, so the class is never loaded. A missing class is a "no", not an error |
| Finding out why a bean is missing is hard | `--debug` prints a report of every condition and its reason |

---

## The mechanism, in four sentences

1. `@SpringBootApplication` includes `@EnableAutoConfiguration`, which imports
   `AutoConfigurationImportSelector`.
2. That selector reads every `META-INF/spring/*.AutoConfiguration.imports` file on the classpath —
   a plain list of class names, worth opening once.
3. The list is **sorted** (alphabetically, then by `@AutoConfigureOrder`, then by
   `@AutoConfigureAfter` / `@AutoConfigureBefore`) and cheaply **filtered** by classpath checks.
4. Each surviving class is parsed like any other `@Configuration`, **after** yours, with its
   conditions evaluated twice: once for the class, then once per `@Bean` method.

<!-- widget:path:condition-evaluation -->

---

## Why auto-configuration can use `@ConditionalOnMissingBean` and you cannot

`@ConditionalOnMissingBean` asks a question about the bean factory *at the moment it is evaluated*.
Auto-configuration gets a reliable answer for exactly two reasons: it is processed **last**, so
your beans are already registered; and the candidates are **sorted**, so two auto-configurations
that both offer a default resolve deterministically.

Neither is true of your own configuration classes. Register the conditional one first and its
condition sees nothing:

```java
@Configuration class A { @Bean @ConditionalOnMissingBean Greeter a() { ... } }
@Configuration class B { @Bean                          Greeter b() { ... } }
```

- `register(A, B)` → **both beans exist**, and the next injection of `Greeter` fails with
  `NoUniqueBeanDefinitionException`.
- `register(B, A)` → only `b`, because by the time `A` was parsed the definition was there.

Same classes, same annotations, opposite outcomes, decided by something no one is looking at. It
holds steady until somebody adds a component scan or renames a package. **Use `@ConditionalOnBean`
and `@ConditionalOnMissingBean` in auto-configuration only** — that is what the Spring Boot
documentation says, and this is the reason.

In your own code, the tools that do work are `@ConditionalOnProperty`, `@Profile`, `@Primary`, and
an explicit `@Bean` method that decides.

→ `ConditionalOnMissingBeanTest`

---

## Conditions on properties

```java
@ConditionalOnProperty("feature.x.enabled")                          // opt in
@ConditionalOnProperty(name = "feature.x.enabled", matchIfMissing = true)   // opt out
@ConditionalOnProperty(name = "feature.mode", havingValue = "fast")  // exact, case-insensitive
```

`matchIfMissing` is the whole design decision: it is the difference between a feature that is off
until somebody switches it on and one that is on until somebody switches it off. Say which you mean.

The default `havingValue` is `""`, which means **"present and not `false`"** — not "equal to
`true`". `feature.x.enabled=banana` switches the feature on. Only the literal `false` switches it
off.

A condition on a `@Configuration` class applies to everything inside it, and short-circuits the
whole class: the `@Bean` methods within are never even read.

→ `ConditionalOnPropertyTest`

---

## Writing one

The whole interface is one method:

```java
class OnFeatureCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String feature = (String) metadata
                .getAnnotationAttributes(ConditionalOnFeature.class.getName()).get("value");
        return context.getEnvironment()
                .getProperty("feature." + feature + ".enabled", Boolean.class, false);
    }
}

@Retention(RUNTIME)
@Conditional(OnFeatureCondition.class)
@interface ConditionalOnFeature { String value(); }
```

`ConditionContext` gives you the `Environment`, the `BeanDefinitionRegistry`, the `ResourceLoader`
and the `ClassLoader` — everything except an instantiated bean, because nothing has been
instantiated yet.

Two details worth copying from Boot's own conditions:

- **Extend `SpringBootCondition` rather than implementing `Condition` directly.** It asks you for a
  `ConditionOutcome` with a human-readable reason, and that reason is what `--debug` prints.
- **Implement `ConfigurationCondition` when your condition asks about beans.** The phase tells the
  parser *when* to ask: `PARSE_CONFIGURATION` while deciding whether to read a class,
  `REGISTER_BEAN` while registering definitions. `OnBeanCondition` declares `REGISTER_BEAN`, which
  is the two lines that make `@ConditionalOnMissingBean` possible at all.

The meta-annotation is what makes it read like Boot's own. See [the annotation
model](annotations.md) for why `MergedAnnotations` finds it.

→ `CustomConditionTest`

---

## Finding out what happened

```bash
java -jar app.jar --debug
```

prints the **conditions evaluation report**: every auto-configuration, matched or not, with the
reason. It is the single most under-used debugging tool in Spring Boot, and it answers the "why is
that bean not there" question in seconds rather than in an afternoon of breakpoints.

`/actuator/conditions` is the same report over HTTP, on a running application.

To rule an auto-configuration out entirely:

```yaml
spring:
  autoconfigure:
    exclude: org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
```

---

## Review checklist

- [ ] Is `@ConditionalOnBean` or `@ConditionalOnMissingBean` used anywhere outside auto-configuration?
- [ ] Does every `@ConditionalOnProperty` say whether the feature is opt-in or opt-out
      (`matchIfMissing`) rather than leaving the reader to guess?
- [ ] Is anything relying on `havingValue = ""` meaning "true"?
- [ ] For a starter you own: is the ordering between your auto-configurations declared, or accidental?
- [ ] Does each custom condition give a reason a stranger could act on in the `--debug` report?
- [ ] Would `--debug` on this application answer the last "why is that bean missing" question you had?

---

## Source map

| Class | Role |
|---|---|
| `context.annotation.ConditionEvaluator` | `shouldSkip` — the whole `@Conditional` mechanism |
| `context.annotation.ConfigurationClassParser` | where a class-level condition short-circuits parsing |
| `context.annotation.ConfigurationClassBeanDefinitionReader` | the second evaluation, per `@Bean` method |
| `context.annotation.ConfigurationCondition` | the two phases |
| `boot.autoconfigure.AutoConfigurationImportSelector` | reads the `.imports` files, excludes, filters |
| `boot.autoconfigure.AutoConfigurationSorter` | the sort that makes back-off deterministic |
| `boot.autoconfigure.condition.SpringBootCondition` | the base class, and the reason strings |
| `boot.autoconfigure.condition.OnBeanCondition` | `@ConditionalOnBean` / `@ConditionalOnMissingBean`, and `REGISTER_BEAN` |
