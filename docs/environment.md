# Environment and profiles, end to end

Verified against **Spring Framework 7.0.9 / Spring Boot 4.1.1**. Every claim below is pinned by a
test in [`labs/lab-environment`](../labs/lab-environment); if a claim and a test ever disagree, the
test is right.

This is the note that answers "why is this property not what I set it to". The answer is always the
same shape, and it is not complicated once you stop thinking of the `Environment` as a map.

---

## The short version

| Assumption | Reality |
|---|---|
| The `Environment` is a map of properties | It is an **ordered list of maps**. Every lookup returns the first hit |
| A profile-specific file replaces the base file | It is **additive**. `application.yaml` is still read, and only the keys the profile file mentions are overridden |
| An empty value means unset | An empty string is a value, and it shadows every source below it |
| `@Profile("default")` means "always" | It means "when no profile is active at all". Setting any profile switches it off |
| Placeholders are resolved when the file is loaded | They are resolved **on read**, so a placeholder can point at a property from a completely different source |

---

## It is a list, not a map

```java
environment.getProperty("colour")
```

walks `MutablePropertySources` in order and returns the first source that has the key. Three
consequences do all the work:

- **First wins.** Not "most specific", not "last loaded" — first in the list.
- **Not having a key is not the same as having no value.** A source without the key is skipped, so
  a high-priority source can override one key and leave everything else to the sources below it.
  This is exactly how profile-specific files work.
- **An empty value stops the search.** `DEMO_VALUE=` in a deployment is not "unset"; it shadows the
  file underneath it, and the symptom is a blank string arriving somewhere that expected a default.

→ `PropertySourceOrderTest`

---

## Boot's order

Boot builds that list for you. The full table is in the reference documentation; these are the
entries that actually come up, highest priority first:

```
command line arguments            --demo.value=x
SPRING_APPLICATION_JSON
servlet / JNDI parameters
java system properties            -Ddemo.value=x
OS environment variables          DEMO_VALUE=x
application-{profile}.yaml        outside the jar, then inside
application.yaml                  outside the jar, then inside
@PropertySource on a @Configuration class
SpringApplication default properties
```

Two practical readings of that list:

- **Environment variables beat your config files.** That is what makes a container image with
  baked-in defaults overridable at deploy time, and it is also why a stray variable on a build
  agent can change behaviour in a way nothing in the repository explains.
- **`setDefaultProperties` is the bottom.** It is the right place for a library or a starter to
  supply a fallback, and the wrong place to try to force a value.

When you need the real answer rather than the documented one, ask the application:
`/actuator/env` lists every source in order, and `--debug` prints the resolved configuration.

→ `BootPrecedenceTest`

---

## Profiles

`@Profile` is a `@Conditional` with a friendlier name, evaluated while `@Configuration` classes are
being parsed — long before any bean exists. That is why it can only include or exclude a whole bean
definition, and never change one later.

```java
@Profile("staging")            // active
@Profile("!production")        // not active
@Profile("staging & eu")       // expressions: & | ! and brackets
@Profile("default")            // when nothing at all is active
```

The `default` profile is the one that catches people. `spring.profiles.default` is `default` unless
you change it, and a `@Profile("default")` bean exists **exactly when no profile is active**.
Activate anything — including a profile that has nothing to do with it — and the bean disappears.

Three habits that avoid most profile pain:

1. **Do not branch business logic on a profile.** Branch configuration on a profile, and inject the
   configuration. `@Profile` on a `@Bean` that picks an implementation is fine; `@Profile` scattered
   through a service is a second application you are not testing.
2. **Keep the base file complete and let profiles override.** Since profile files are additive,
   copying the whole base file into `application-prod.yaml` just gives you two files to keep in
   step.
3. **`spring.profiles.active` cannot be set inside a profile-specific file.** Boot rejects it,
   because it would be a rule that changes which rules apply.

→ `ProfileTest`

---

## Placeholders

Resolution happens **on read**, by `PropertySourcesPropertyResolver`, not when the file is loaded:

```properties
app.name=orders
app.queue=${app.name}-events        # -> orders-events
app.url=${primary:${fallback}}      # defaults can nest
app.missing=${nobody.set.this}      # throws on read, not on load
```

That an unresolvable placeholder **throws** rather than returning the raw text is one of the few
loud failures in configuration. It is also why `@Value` is the safer of the two annotations for a
value that must be present, even though `@ConfigurationProperties` is better for everything else —
see [property binding](property-binding.md).

→ `PropertySourceOrderTest`

---

## Review checklist

- [ ] For any surprising value: which source is it coming from? `/actuator/env` answers in seconds.
- [ ] Is any deployment setting a variable to the empty string and expecting a default?
- [ ] Does a profile-specific file duplicate the base file instead of overriding it?
- [ ] Is any business logic branching on `@Profile` rather than on injected configuration?
- [ ] Is anything relying on `@Profile("default")` while another profile is being activated in
      production?
- [ ] Is a secret sitting in `application.yaml` where an environment variable or a secrets manager
      should be?

---

## Source map

| Class | Role |
|---|---|
| `core.env.AbstractEnvironment` | `getProperty`, active and default profiles |
| `core.env.MutablePropertySources` | the ordered list, `addFirst` / `addLast` / `addBefore` |
| `core.env.PropertySourcesPropertyResolver` | the first-hit walk, and placeholder resolution |
| `core.env.SystemEnvironmentPropertySource` | the source that makes `MY_APP_X` work |
| `boot.context.config.ConfigDataEnvironmentPostProcessor` | how Boot finds and orders `application*.yaml` |
| `boot.context.config.ConfigDataEnvironmentContributors` | profile-specific files, `spring.config.import` |
| `core.env.Profiles` | the `& | !` expression parser |
