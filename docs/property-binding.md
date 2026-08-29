# Property binding, end to end

Verified against **Spring Framework 7.0.9 / Spring Boot 4.1.1**. Every claim below is pinned by a
test in [`labs/lab-binding`](../labs/lab-binding); if a claim and a test ever disagree, the test is
right.

Configuration is the part of an application that is read once at startup and never changes again.
That makes it the easiest part to get right — and the part most likely to be wrong in production
for three weeks before anybody notices, because nothing fails when a property does not bind.

---

## The short version

| Assumption | Reality |
|---|---|
| `@Value` and `@ConfigurationProperties` are two spellings of one thing | Only the second one goes through the Binder. `@Value` gets no relaxed binding and no conversion beyond `ConversionService` |
| A missing property is an error | A missing `@Value` fails the context. A missing bound property is silently left at `null` or `0` |
| `MY_APP_TIMEOUT` binds because the binder is clever | It binds because the source says it holds environment variables. The same key in an ordinary property source binds to nothing |
| A `@ConfigurationProperties` class needs setters | Since Boot 3, one parameterised constructor means constructor binding. A record just works |
| Map keys get relaxed too | They do not. A map key is data, and keeps its exact spelling |

---

## Relaxed binding, and where it stops

These four all reach the same field:

```properties
my.service.read-timeout=5     # canonical
my.service.readTimeout=5
my.service.read_timeout=5
MY_SERVICE_READTIMEOUT=5      # only from an environment-variable source
```

The mapping is done by `ConfigurationPropertyName`, which normalises everything to lower-case
alphanumerics-and-dashes before comparing. **Always write the canonical kebab-case form** in your
own files: it is the one the metadata, the IDE completion and the documentation use.

The last line is the one worth understanding properly. The uppercase-underscore form is not
handled by the binder in general — it is handled by `SystemEnvironmentPropertyMapper`, which is
only applied to a property source that declares itself to hold environment variables. Put
`MY_SERVICE_READTIMEOUT` in an `application.properties` file and it binds to nothing at all.

**`@Value` gets none of this.** `@Value("${my.service.readTimeout}")` is a placeholder lookup
against the raw property names, so it matches exactly or it fails the context with
`Could not resolve placeholder`. That failure is the one good thing about `@Value`: it is loud.

| | `@Value` | `@ConfigurationProperties` |
|---|---|---|
| relaxed names | no | yes |
| missing value | context fails | silently `null` / `0` |
| default | `${x:fallback}` | `@DefaultValue`, or the field initialiser |
| validation | none | `@Validated` |
| type safety | per-field | per-object, and it shows up in IDE completion |
| SpEL | yes | no |

Use `@Value` for a one-off in a small class. Use `@ConfigurationProperties` for everything a
feature actually needs, which is nearly always more than one property.

→ `RelaxedBindingTest`

---

## Constructor binding

A record with `@ConfigurationProperties` binds through its constructor. No `@ConstructorBinding`,
no setters, nothing mutable:

```java
@ConfigurationProperties(prefix = "client")
record ClientProperties(
        String url,
        @DefaultValue("2") int retries,
        @DefaultValue Timeouts timeouts,          // built from its own defaults
        Proxy proxy,                               // null when nothing under it is set
        @DefaultValue List<String> hosts,
        @DefaultValue Map<String, String> headers) {

    record Timeouts(@DefaultValue("1000") int connect, @DefaultValue("2000") int read) { }
    record Proxy(String host, int port) { }
}
```

Four rules that are not obvious from looking at it:

- **A nested object with nothing set under it is `null`**, unless you mark it `@DefaultValue`, in
  which case it is constructed from its own defaults. That single annotation removes most of the
  null checks people write around configuration.
- **`@DefaultValue` on a collection means empty, not null.** Same reasoning.
- **Lists bind from either form**: `hosts[0]=a`, `hosts[1]=b`, or `hosts=a,b`.
- **Map keys keep their spelling.** `client.headers.X-Trace-Id` stays `X-Trace-Id`, because the key
  is data rather than a property name.

`@ConfigurationProperties` on an `@Bean` method is always **setter** binding — the object exists
before the binder sees it. That is the way to bind a third-party class you cannot annotate, and the
reason a class with a no-arg constructor and setters keeps the old behaviour.

→ `ConstructorBindingTest`

---

## Conversion

| Written | Bound to | Note |
|---|---|---|
| `30s`, `PT30S`, `500ms` | `Duration` | a bare number is **milliseconds** unless `@DurationUnit` says otherwise |
| `10MB`, `512KB` | `DataSize` | `@DataSizeUnit` for the bare-number case |
| `read-only`, `READ_ONLY`, `readOnly` | an enum constant | matched loosely, like property names |
| `a,b,c` | `List<String>` | or indexed keys |
| `host:8080` | your own type | a `Converter` bean annotated `@ConfigurationPropertiesBinding` |

A value that cannot be converted **does** fail the context, and the message names the property.
That is the one case where binding is loud.

→ `ConversionAndValidationTest`

---

## Fail at startup

```java
@Validated
@ConfigurationProperties(prefix = "client")
record ClientProperties(@NotEmpty String url, @Min(0) int retries) { }
```

With `@Validated`, a bad value throws `ConfigurationPropertiesBindException` during refresh and
names every failing property at once. Without it, the same configuration starts cleanly and the
application discovers the problem when it first tries to use it — usually as a
`NullPointerException` in a stack trace that has nothing to do with configuration.

The cost is one annotation and a validation dependency. Put it on every properties class that has a
value the application cannot invent a sensible answer for.

→ `ConversionAndValidationTest`

---

## Review checklist

- [ ] Is any `@Value` doing work that a `@ConfigurationProperties` type should own?
- [ ] Is every properties class that has a required value `@Validated`?
- [ ] Are the property names written in canonical kebab-case?
- [ ] Does any nested configuration object need `@DefaultValue` to stop it being null?
- [ ] Is anything relying on `MY_APP_X` from a source that is not the environment?
- [ ] Is there a `spring-configuration-metadata` entry, so the IDE and `/actuator/configprops` know
      about it? (`spring-boot-configuration-processor` generates it.)
- [ ] Does a wrong value fail the build, the startup, or the first request at 3am?

---

## Source map

| Class | Role |
|---|---|
| `boot.context.properties.bind.Binder` | the whole binding entry point |
| `boot.context.properties.source.ConfigurationPropertyName` | normalisation — the actual definition of "relaxed" |
| `boot.context.properties.source.SystemEnvironmentPropertyMapper` | the `MY_APP_X` mapping, and why it only applies to some sources |
| `boot.context.properties.bind.ValueObjectBinder` | constructor binding, `@DefaultValue` |
| `boot.context.properties.bind.JavaBeanBinder` | setter binding |
| `boot.context.properties.ConfigurationPropertiesBindingPostProcessor` | where binding is triggered, per bean |
| `boot.convert.ApplicationConversionService` | durations, data sizes, delimited lists |
