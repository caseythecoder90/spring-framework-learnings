# The request lifecycle, end to end

Verified against **Spring Framework 7.0.9 / Spring Boot 4.1.1**. Every claim below is pinned by a
test in [`labs/lab-web`](../labs/lab-web); if a claim and a test ever disagree, the test is right.

`DispatcherServlet.doDispatch` is the front controller pattern written out in about eighty readable
lines. Read it once and every question in this note has an obvious place to look.

---

## How to work through this note

1. **Read "Before this note".** The Servlet API, briefly. Spring MVC is one servlet, and knowing
   what a servlet and a filter are makes the whole request path legible.
2. **Run `RequestLifecycleTest` and read it.**
   ```bash
   ./mvnw -pl labs/lab-web -am test -Dtest=RequestLifecycleTest
   ```
   It records the order of filter, interceptor, handler and advice for one request.
3. **Read "One request, end to end"** against that recording.
4. **Run and read `ArgumentBindingTest`**, then "Getting values into the method". The unannotated
   complex parameter is the surprise here.
5. **Run and read `ExceptionHandlingTest`**, then the exception section. The resolution order is
   worth internalising before you need it at 2am.
6. **Finish with "What this changes for you."**

---

## What you will be able to answer afterwards

- In what order do my filter, interceptor, controller and `@ControllerAdvice` actually run?
- Why does an exception from a filter never reach my `@ControllerAdvice`?
- Why is an unannotated method parameter bound from query parameters instead of the body?
- Which `@ExceptionHandler` wins when several could match?

---

## Before this note

**No earlier note is strictly required**, though [the proxy model](proxies.md) helps, since
controllers are ordinary beans and can be proxied like any other.

**The Servlet API you need.** Four ideas:

*A servlet handles requests for a URL pattern.* Spring MVC is **one** servlet —
`DispatcherServlet` — mapped by default to `/`. Everything Spring does with routing, argument
binding and view resolution happens *inside* that single servlet.

*A filter wraps the servlet.* Filters form a chain around it:

```
filter → filter → DispatcherServlet → controller
```

That nesting is the single most consequential fact in this note. Everything Spring MVC knows about
— interceptors, `@ControllerAdvice`, `@ExceptionHandler` — lives **inside** the servlet. A filter is
outside it, so an exception thrown in a filter has already left Spring MVC's reach, and the servlet
container handles it instead.

*Exceptions crossing the servlet boundary get wrapped.* The Servlet spec says a servlet may throw
`ServletException` or `IOException`, so anything else is wrapped on the way out. A filter's `catch`
block sees a `ServletException`, and the exception your controller actually threw is one
`getCause()` away.

*One request, one thread.* The classic servlet model assigns a thread per request for the duration.
That is why `ThreadLocal`-based mechanisms — the security context, request scope, and a
[transaction](transactions.md) — work at all in a web application, and why they stop working the
moment you hand the work to another thread.

---

## One request, end to end

```
   ┌─ Filter.doFilter                       servlet container; no idea what a controller is
   │    ┌─ DispatcherServlet.doDispatch
   │    │    HandlerMapping        → which method
   │    │    HandlerAdapter        → how to call it
   │    │    interceptor.preHandle
   │    │      argument resolvers  → @RequestParam, @RequestBody, Principal, yours
   │    │        YOUR CONTROLLER METHOD
   │    │      return value handlers → @ResponseBody, ResponseEntity, a view name
   │    │        message converters   → content negotiation, serialisation
   │    │    interceptor.postHandle
   │    │    render
   │    │    interceptor.afterCompletion
   │    └─ (exception? → HandlerExceptionResolver, then @ExceptionHandler)
   └─ back in the filter, with a response
```

The nesting is the whole lesson. Three consequences:

- **`postHandle` is skipped when the handler throws.** Anything that must always happen — closing a
  resource, clearing an MDC, stopping a timer — belongs in `afterCompletion`, which receives the
  exception as a parameter.
- **An interceptor whose `preHandle` returns `false` stops the request there**, and its own
  `afterCompletion` is not called either.
- **An exception thrown in a filter never reaches `@ControllerAdvice`.** It is outside the
  `DispatcherServlet`, so what the client gets is the container's error page, not your error format.
  This is the shape of every "our authentication failures don't use our error schema" bug. The fix
  is an error-handling filter of your own, wrapped around it.

→ `RequestLifecycleTest`

<!-- widget:path:request-dispatch -->

---

## Getting values into the method

Every parameter is resolved by some `HandlerMethodArgumentResolver`, chosen by asking each in turn
whether it `supportsParameter`. First match wins, and the answer is cached per parameter.

| Written | What happens | Missing value |
|---|---|---|
| `@RequestParam String q` | query or form parameter | **400** — required is the default |
| `@RequestParam(defaultValue = "1") int page` | as above, with a fallback | uses the default |
| `@PathVariable String id` | from the URI template | 500 — the mapping should have matched |
| `@RequestBody NewOrder order` | an `HttpMessageConverter`, usually Jackson | 400 unreadable, 415 wrong content type |
| `String term` *(no annotation)* | **treated as `@RequestParam`** | null |
| `Filters filters` *(no annotation)* | **treated as `@ModelAttribute`** | an object with null fields |

The last row is the one that costs an afternoon. A parameter object with no annotation is not read
from the request body — it is populated field by field from query and form parameters. POST JSON at
a method like that and it succeeds, with every field null, and nothing anywhere says why.

Two more defaults worth stating out loud:

- **A type mismatch is a 400, not a 500.** `?page=banana` produces
  `MethodArgumentTypeMismatchException`, which the default resolver already maps to a client error.
- **`@Valid` is not implied.** `@NotBlank` on a record does nothing until something asks for
  validation. With `@Valid` on the parameter, a violation becomes
  `MethodArgumentNotValidException` and a 400 before your method runs.

→ `ArgumentBindingTest`

---

## Turning an exception into a response

Four mechanisms, in the order the resolvers are asked:

1. **`@ExceptionHandler` on the controller** — searched first, so it can override a global handler
   for one controller.
2. **`@ExceptionHandler` in a `@ControllerAdvice`** — the application-wide default. Order several
   with `@Order`, and scope them with `assignableTypes` or `basePackages`.
3. **`@ResponseStatus` on the exception class** — a status with no handler at all. Good for a
   `NotFoundException` that is always a 404.
4. **`ResponseStatusException`** — the status on the instance, for when it is a runtime decision.
   No new class, no handler.

Within a set of handlers the match is by **hierarchy depth**, not by declaration order: a handler
for `NotFoundException` beats one for `RuntimeException`, wherever they are written. Same scoring
idea as `rollbackFor` in [transactions](transactions.md).

If nothing matches the exception itself, Spring tries again with its **cause**, which saves writing
a handler for every wrapper type a framework introduces.

The trap: **`@ExceptionHandler(RuntimeException.class)` on a controller is a catch-all**, and it is
searched before everything above. Add one "so we log everything" and that controller's
`@ResponseStatus` exceptions, its `ResponseStatusException`s and the global advice all stop working
— quietly, and only for that controller.

For a consistent error format across an application, extend `ResponseEntityExceptionHandler` in a
`@ControllerAdvice`: it already handles Spring MVC's own exceptions and produces RFC 9457
`ProblemDetail` bodies.

→ `ExceptionHandlingTest`

---

## What this changes for you

Now that the mechanism is in place, the short version — the things that are true and
surprise people:

| Assumption | Reality |
|---|---|
| A filter and an interceptor are much the same thing | The filter is **outside** the `DispatcherServlet`. An exception thrown in one never reaches `@ControllerAdvice` |
| `postHandle` always runs | It is skipped whenever the handler throws. `afterCompletion` is the one that always runs |
| An unannotated method parameter is ignored | A simple type becomes `@RequestParam`; a **complex type becomes `@ModelAttribute`**, bound from query parameters, not from the body |
| `@ExceptionHandler` methods are tried in the order written | The one **closest to the thrown type** wins, and the controller's own are searched before any advice |
| `@Valid` is implied by the constraint annotations | Without `@Valid` the constraints are inert |
| A filter sees the exception my controller threw | It sees a `ServletException` wrapping it, or a status code if something resolved it |

---

## Review checklist

- [ ] Does anything in a filter throw? If so, who turns that into your error format?
- [ ] Is anything that must always run sitting in `postHandle` rather than `afterCompletion`?
- [ ] Any controller method with an unannotated complex parameter that was meant to be `@RequestBody`?
- [ ] Any `@RequestBody` type carrying constraints but no `@Valid`?
- [ ] Any `@ExceptionHandler(Exception.class)` or `(RuntimeException.class)` on a controller rather
      than in the advice?
- [ ] Do error responses have one shape across the whole application, including the framework's own
      400s and 405s?
- [ ] Is a `@RequestParam` required by accident, or optional by accident?

---

## Source map

| Class | Role |
|---|---|
| `web.servlet.DispatcherServlet` | `doDispatch`, `processDispatchResult`, `processHandlerException` |
| `web.servlet.handler.AbstractHandlerMethodMapping` | `lookupHandlerMethod` — URL to method, and ambiguity |
| `web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter` | assembles resolvers, handlers and binders per request |
| `web.method.support.InvocableHandlerMethod` | `getMethodArgumentValues` — the parameter loop |
| `web.method.support.HandlerMethodArgumentResolverComposite` | first-match-wins resolver selection |
| `web.servlet.mvc.method.annotation.AbstractMessageConverterMethodProcessor` | content negotiation and serialisation |
| `web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver` | controller handlers first, then advice |
| `web.method.annotation.ExceptionHandlerMethodResolver` | the depth scoring, and the cause fallback |
