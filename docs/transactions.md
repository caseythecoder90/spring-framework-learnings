# `@Transactional`, end to end

Verified against **Spring Framework 7.0.9 / Spring Boot 4.1.1**. Every claim below is pinned by a
test in [`labs/lab-transactions`](../labs/lab-transactions); if a claim and a test ever disagree,
the test is right.

The tests run against a real embedded database with a real `JdbcTransactionManager`, because
suspension and savepoints are not things you can fake with a stub and still learn anything from.
Every assertion comes down to the same question: **what is left in the table afterwards?**

---

## How to work through this note

1. **Read "Before this note".** It is short, and the JDBC half of it is what a transaction actually
   is at the driver level.
2. **Run `TransactionBoundariesTest` and read it.**
   ```bash
   ./mvnw -pl labs/lab-transactions -am test -Dtest=TransactionBoundariesTest
   ```
   Where a transaction starts and ends, and the places `@Transactional` silently does nothing.
3. **Read "From annotation to behaviour".**
4. **Play with the propagation matrix below**, then run and read `PropagationTest`. Do these
   together — the widget tells you what happens, the test proves it against a real database.
5. **Run and read `RollbackRulesTest`**, then the rollback sections. The rollback-only trap is the
   one that causes real incidents.
6. **Finish with "What this changes for you."**

---

## What you will be able to answer afterwards

- Which exceptions roll a transaction back, and which quietly commit?
- What is the actual difference between `REQUIRES_NEW` and `NESTED`?
- Why do I get `UnexpectedRollbackException` from a method that caught its own exception?
- Where does `@Transactional` silently do nothing at all?

---

## Before this note

**Read two notes first.** [JdbcTemplate and DataSource](jdbctemplate.md) for where connections come
from, because a transaction is a property of one connection. And [the proxy model](proxies.md),
because `@Transactional` is an interceptor and inherits every proxy limitation.

**The JDBC you need.** A database transaction is not a Spring concept. At the driver level it is
three calls on a single `Connection`:

```java
connection.setAutoCommit(false);   // begin
// ... statements ...
connection.commit();               // or connection.rollback();
```

That is all `PlatformTransactionManager` is automating. Two consequences follow directly:

*A transaction is bound to one connection.* Everything that must be in the transaction has to run
on that same `Connection` object. Spring binds it to the current **thread** in
`TransactionSynchronizationManager`, which is precisely why a transaction does not follow work onto
an [`@Async`](async.md) thread — a different thread looks up the binding and finds nothing.

*Savepoints are a JDBC feature, not a Spring one.* `connection.setSavepoint()` marks a point you can
roll back to without abandoning the whole transaction. That single call is the entire difference
between `NESTED` (a savepoint on the existing connection) and `REQUIRES_NEW` (a genuinely separate
transaction on a second connection). Knowing that makes the propagation table below read as
mechanics rather than as a list to memorise.

---

## From annotation to behaviour

The same four pieces as caching, retry and `@Async`, which is the point of reading them in that
order:

```
@EnableTransactionManagement          (Boot adds this for you)
  └─ @Import(TransactionManagementConfigurationSelector)
       └─ ProxyTransactionManagementConfiguration
            ├─ AnnotationTransactionAttributeSource   parses @Transactional -> TransactionAttribute
            ├─ TransactionInterceptor                 the advice
            └─ BeanFactoryTransactionAttributeSourceAdvisor
```

Everything interesting happens in one method,
`TransactionAspectSupport.invokeWithinTransaction`. Read it once, top to bottom, and most of this
note becomes obvious.

<!-- widget:path:transactional-call -->

---

## The propagation matrix

Seven modes, and the whole thing is one switch statement in
`AbstractPlatformTransactionManager.handleExistingTransaction`. Learning it as a table is what makes
people get it wrong; learning it as "what happens to the row I just wrote" is what makes it stick.

<!-- widget:propagation-matrix -->

| Mode | No transaction running | One already running |
|---|---|---|
| `REQUIRED` *(default)* | starts one | **joins** it |
| `REQUIRES_NEW` | starts one | **suspends** it, starts a second on a second connection |
| `NESTED` | starts one | takes a **savepoint** in the same one |
| `MANDATORY` | throws `IllegalTransactionStateException` | joins it |
| `NEVER` | runs with none | throws `IllegalTransactionStateException` |
| `NOT_SUPPORTED` | runs with none | **suspends** it, runs with none |
| `SUPPORTS` | runs with none | joins it |

Three things in that table do more work than the words suggest.

**Joins means one transaction.** Not "a transaction inside a transaction" — there is exactly one,
with one commit at the end of the outermost method. `REQUIRED`, `MANDATORY` and `SUPPORTS` all mean
the same thing once a transaction exists.

**Suspends means a second connection.** `REQUIRES_NEW` under load doubles your connection
requirement for the duration of the inner call, and the outer transaction is still holding its own
locks while the inner one waits for them. `REQUIRES_NEW` on something the outer transaction has
already touched is a deadlock waiting for enough traffic.

**Runs with none means autocommit.** `NOT_SUPPORTED` and a no-transaction `SUPPORTS` are not
"transaction-free", they are one transaction per statement. Two writes are two commits, and a
failure between them leaves half the work done and nothing to roll back.

→ `PropagationTest`

---

## The rollback-only trap

The most expensive five lines in Spring:

```java
@Transactional
public void placeOrder(Order order) {
    orders.insert(order);
    try {
        audit.record(order);        // @Transactional, REQUIRED, throws
    }
    catch (Exception e) {
        log.warn("audit failed, carrying on", e);   // it did not carry on
    }
}
```

`audit.record` joined the same transaction. When it threw, its interceptor could not roll anything
back on its own — it is a participant, not the owner — so instead it set `rollbackOnly` on the
shared transaction and rethrew. You caught the exception, so `placeOrder` returns normally, so the
interceptor tries to commit, so `AbstractPlatformTransactionManager` sees the flag and throws:

```
UnexpectedRollbackException: Transaction silently rolled back because it has been marked as rollback-only
```

The order insert is gone too. The caller gets an exception it has never heard of, from a line that
does not appear in your code, and the log says the audit failure was handled.

Three ways out:

1. **`REQUIRES_NEW` on the inner method.** A suspended transaction has its own rollback flag, so
   catching really does recover. Costs a connection.
2. **`NESTED`.** A savepoint, so the rollback is scoped to the inner call without a second
   connection. Needs savepoint support — `JdbcTransactionManager` yes, JPA and JTA generally no.
3. **Do not call a `@Transactional` method for work you intend to treat as optional.** Usually the
   right answer: publish an event and handle it after the commit instead.

→ `PropagationTest.markingTheInnerTransactionRollbackOnlyPoisonsTheOuterCommit`

---

## Rollback rules

`DefaultTransactionAttribute.rollbackOn` is one line:

```java
return (ex instanceof RuntimeException || ex instanceof Error);
```

So a checked exception thrown out of a `@Transactional` method **commits everything written before
it**. This is documented, deliberate, and still the default most likely to lose data quietly.

```java
@Transactional(rollbackFor = Exception.class)     // the usual fix
```

When both `rollbackFor` and `noRollbackFor` match, the winner is not the one written first — it is
the one **fewer superclasses away** from what was actually thrown. `RuleBasedTransactionAttribute`
scores each rule by depth in the exception hierarchy and takes the shallowest, so
`rollbackFor = Exception.class, noRollbackFor = IllegalStateException.class` commits on an
`IllegalStateException` and rolls back on everything else.

Two more worth knowing:

- **Catching it inside the method is not "handling an exception"** as far as the transaction is
  concerned. Rollback is decided by what leaves the method *through the proxy*, so an exception you
  catch and log never reaches the interceptor at all.
- **`TransactionAspectSupport.currentTransactionStatus().setRollbackOnly()`** rolls back without
  throwing anything at the caller. Occasionally exactly what you want.

→ `RollbackRulesTest`

---

## Where the transaction stops

Four boundaries, all from the same two facts: it is started by a **proxy**, and it lives in a
**`ThreadLocal`**. Cross either and there is no transaction, with no error either way.

| You did this | What you got |
|---|---|
| Called a `@Transactional` method from another method of the same bean | no transaction |
| Put `@Transactional` on a package-private or protected method | no transaction |
| Handed work to an `@Async` method or a raw thread | no transaction on that thread |
| Called it on `this` inside a lambda in the same class | no transaction |

The non-public case is worth naming precisely, because it is not a compile error and not a warning:
`AnnotationTransactionAttributeSource` has `publicMethodsOnly = true`, so
`getTransactionAttribute` returns `null` and no advice is ever applied.

The thread-local is `TransactionSynchronizationManager`. It binds the `Connection` to the current
thread, which is why:

- every `JdbcTemplate` call inside one transaction gets the **same** connection, and every call
  outside one gets a fresh one;
- the transaction does not follow you onto an executor;
- `@TransactionalEventListener` has somewhere to hang its callbacks — see
  [application events](events.md).

→ `TransactionBoundariesTest`

---

## Synchronization callbacks

Registered work runs in a fixed order, and all of it happens **inside** the proxy call — after your
method body has finished, before the caller gets control back:

```
method body
  beforeCommit
  beforeCompletion
  <commit>
  afterCommit
  afterCompletion
caller resumes
```

On a rollback, `beforeCommit` and `afterCommit` are both skipped; `beforeCompletion` and
`afterCompletion` still run. That asymmetry is exactly what `@TransactionalEventListener(phase =
AFTER_COMMIT)` is built on.

→ `TransactionBoundariesTest`

---

## `readOnly` is a hint, and it means different things

`@Transactional(readOnly = true)` does **not** mean "this will fail if you write".

| Transaction manager | What `readOnly` actually does |
|---|---|
| `JdbcTransactionManager` | calls `Connection.setReadOnly(true)`, which most drivers treat as advisory. The write goes through |
| ... with `enforceReadOnly = true` | additionally issues `SET TRANSACTION READ ONLY`, and then the database refuses |
| `JpaTransactionManager` | sets the Hibernate flush mode to `MANUAL`, so changes are simply never flushed — no error, no save |

What it always does is set `TransactionSynchronizationManager.isCurrentTransactionReadOnly()`, which
is how routing `DataSource`s send read-only transactions to a replica. Useful, and nothing to do
with safety.

→ `TransactionBoundariesTest`

---

## What this changes for you

Now that the mechanism is in place, the short version — the things that are true and
surprise people:

| Assumption | Reality |
|---|---|
| An exception rolls the transaction back | Only `RuntimeException` and `Error`. A checked exception **commits** |
| Catching the inner exception means you recovered | With `REQUIRED` it does not. The commit throws `UnexpectedRollbackException` |
| `REQUIRES_NEW` and `NESTED` are much the same | One takes a second connection, the other a savepoint. Only one survives an outer rollback |
| `readOnly = true` stops writes | It is a hint. On plain JDBC the write goes through |
| `@Transactional` works wherever you put it | Not on a non-public method, not on a self-call, not on another thread — and it never says so |

---

## Review checklist

- [ ] Any `catch` around a call to a `REQUIRED` `@Transactional` method? That is the rollback-only
      trap unless the inner method is `REQUIRES_NEW` or `NESTED`.
- [ ] Any checked exception thrown out of a `@Transactional` method without `rollbackFor`?
- [ ] Is the method public, and called from outside its own bean?
- [ ] Does the transaction span a network call, a queue publish, or an `@Async` hand-off? Those do
      not roll back, and they hold a connection while they wait.
- [ ] Is `REQUIRES_NEW` touching rows the outer transaction has already locked?
- [ ] Does `readOnly = true` here mean what the reader thinks it means?
- [ ] Is the transaction opened at the level where the unit of work actually is — usually the
      service method, not the repository?

---

## Source map

| Class | Role |
|---|---|
| `transaction.annotation.ProxyTransactionManagementConfiguration` | what `@EnableTransactionManagement` imports |
| `transaction.annotation.AnnotationTransactionAttributeSource` | `@Transactional` to `TransactionAttribute`, and `publicMethodsOnly` |
| `transaction.interceptor.TransactionInterceptor` | the thin adapter |
| `transaction.interceptor.TransactionAspectSupport` | `invokeWithinTransaction`: the whole feature in one method |
| `transaction.interceptor.RuleBasedTransactionAttribute` | `rollbackFor` / `noRollbackFor`, resolved by hierarchy depth |
| `transaction.support.AbstractPlatformTransactionManager` | `getTransaction` and `handleExistingTransaction`: the propagation matrix |
| `transaction.support.TransactionSynchronizationManager` | the thread-local that all of it hangs off |
| `jdbc.support.JdbcTransactionManager` | begin, commit, savepoint, and `setReadOnly` |
