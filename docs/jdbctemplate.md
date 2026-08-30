# JdbcTemplate and DataSource, end to end

Verified against **Spring Framework 7.0.9 / Spring Boot 4.1.1**. Every claim below is pinned by a
test in [`labs/lab-jdbc`](../labs/lab-jdbc); if a claim and a test ever disagree, the test is right.

First note in the Data track, and deliberately the bottom of the stack. Transactions and Hibernate
both sit on top of the connection handling described here, so this is the layer to understand
first.

---

## How to work through this note

1. **Read "Before this note".** Plain JDBC, briefly. Everything Spring does here is a wrapper over
   five JDBC types, and the wrapper only makes sense if you can name them.
2. **Run `ConnectionHandlingTest` and read it.**
   ```bash
   ./mvnw -pl labs/lab-jdbc -am test -Dtest=ConnectionHandlingTest
   ```
   Four tests, and they establish the single most important fact in this note: whether you are in a
   transaction decides where the connection comes from.
3. **Read "Where the connection comes from".**
4. **Run and read `ExceptionTranslationTest`**, then the translation section. Pay attention to
   `queryForObject` — its zero-row behaviour surprises everyone once.
5. **Run and read `QueryApiTest`**, then "Three APIs".
6. **Finish with "What this changes for you."**

---

## What you will be able to answer afterwards

- How many database connections does a method actually take, and does a transaction change that?
- Why does no Spring data access method declare `throws SQLException`?
- Why does `queryForObject` throw when nothing matches, instead of returning `null`?
- Which of `JdbcTemplate`, `NamedParameterJdbcTemplate` and `JdbcClient` should new code use?

---

## Before this note

This is the bottom of the Data track, so there is no earlier note to read first. If you are heading
for [transactions](transactions.md) next, read this one before it — that note builds directly on
the connection handling described here.

**The JDBC you need.** Five types, and Spring wraps all of them:

| Type | What it is |
|---|---|
| `DataSource` | a factory for connections, almost always a pool |
| `Connection` | one session with the database; a transaction lives on one of these |
| `PreparedStatement` | a compiled statement with `?` placeholders |
| `ResultSet` | a cursor over rows, positioned with `next()` |
| `SQLException` | **checked**, and vendor-specific |

Raw JDBC looks like this, and the shape is why `JdbcTemplate` exists:

```java
try (Connection c = dataSource.getConnection();
     PreparedStatement ps = c.prepareStatement("SELECT name FROM widget WHERE id = ?")) {
    ps.setString(1, id);
    try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getString("name") : null;
    }
}                                    // and every line of it throws SQLException
```

Three things about that are worth naming, because each maps to something Spring does:

*A pooled `close()` does not close anything.* `DataSource.getConnection()` usually hands you a
wrapper from a pool; calling `close()` returns it to the pool. So "leaking a connection" means
failing to return it, and the pool then runs dry — which presents as your application hanging, not
as an error.

*`SQLException` is checked and vendor-specific.* The same duplicate-key violation is error code
`23505` on H2, `23505` on Postgres and `1062` on MySQL. Handling that portably by hand is
miserable, which is the entire motivation for exception translation.

*Autocommit is on by default.* Unless something calls `setAutoCommit(false)`, every statement
commits as it executes. A transaction is that flag being turned off and a `commit()` or
`rollback()` arriving later, on **that same `Connection` object** — which is why the connection
question above matters so much.

---

## Where the connection comes from

Every operation goes through `DataSourceUtils.doGetConnection`, and it has one important branch:

```
is a connection bound to this thread by a transaction?
  ├─ yes  -> reuse it, and do not close it on the way out
  └─ no   -> take a fresh one from the pool, and close it when done
```

So three statements outside a transaction take three connections. The same three inside a
transaction take one. The binding lives in `TransactionSynchronizationManager` — the same
thread-local that means a transaction does not survive an
[`@Async` hand-off](async.md).

The practical consequences:

- A method doing many queries without a transaction is doing many pool round trips. Wrapping it in
  a read-only transaction is often the cheapest performance fix available.
- Anything that takes a `Connection` by hand must release it through
  `DataSourceUtils.releaseConnection`, not `connection.close()`. Closing a transactional connection
  directly is how leaks and "connection is closed" errors appear halfway through a transaction.

→ `ConnectionHandlingTest`

---

## Exception translation

Nothing in Spring's data access API throws `SQLException`. Every driver failure becomes an
unchecked `DataAccessException`, so "duplicate key" means the same thing on Postgres, MySQL and H2:

| Driver failure | Spring exception |
|---|---|
| unique constraint | `DuplicateKeyException` |
| syntax error, unknown column | `BadSqlGrammarException` |
| zero rows from `queryForObject` | `EmptyResultDataAccessException` |
| more than one row | `IncorrectResultSizeDataAccessException` |

**Which translator does this changed in Framework 6.** The default is now
`SQLExceptionSubclassTranslator`, working from the JDBC 4 `SQLException` subclasses and SQL state.
The vendor error-code table (`SQLErrorCodeSQLExceptionTranslator`) is only used if you supply your
own `sql-error-codes.xml`. If you remember configuring error codes years ago, that knowledge is now
mostly historical.

The original `SQLException` is always kept as the cause, so nothing is lost.

→ `ExceptionTranslationTest`

---

## `queryForObject` throws on zero rows

The most surprising method in the API. Not null, not an `Optional` — an
`EmptyResultDataAccessException`. Two rows throws as well.

```java
jdbc.queryForObject("SELECT name FROM widget WHERE id = ?", String.class, id);  // throws if absent
jdbc.queryForList("SELECT name FROM widget WHERE id = ?", String.class, id);    // empty list
client.sql(...).param("id", id).query(Widget.class).optional();                 // Optional.empty()
```

Catching `EmptyResultDataAccessException` to mean "not found" works but reads badly. Prefer
`JdbcClient.optional()` in new code.

---

## Three APIs

All three are current, and you will meet all three.

**`JdbcTemplate`** — positional `?` parameters. Fine, and everywhere.

**`NamedParameterJdbcTemplate`** — named parameters, and the one feature that matters:
list expansion for `IN` clauses. A positional `?` cannot take a list, and hand-building
`IN (...)` from a loop is where SQL injection usually gets in.

```java
named.query("SELECT ... WHERE id IN (:ids)", Map.of("ids", List.of("a", "b")), MAPPER);
```

**`JdbcClient`** (Framework 6.1) — a fluent facade over both, and the default choice for new code:

```java
client.sql("SELECT id, name FROM widget WHERE id = :id")
      .param("id", id)
      .query(Widget.class)    // maps to a record with no RowMapper
      .optional();            // no exception on zero rows
```

It picks the underlying template from which `param()` overload you use, maps straight to records,
and fixes the `queryForObject` sharp edge.

→ `QueryApiTest`

---

## What this changes for you

Now that the mechanism is in place, the short version — the things that are true and
surprise people:

| Assumption | Reality |
|---|---|
| One `JdbcTemplate` call, one connection | Only outside a transaction. Inside, they all share one |
| `queryForObject` returns null when nothing matches | It **throws** |
| Exception translation uses vendor error codes | Not since Framework 6. It uses JDBC 4 subclasses |
| `JdbcTemplate` is the current API | `JdbcClient` has been the one to reach for since 6.1 |
| `?` can take a list for an `IN` clause | It cannot. That is what named parameters are for |

---

## Review checklist

- [ ] Does a method run several statements outside a transaction, taking a connection each time?
- [ ] Any `queryForObject` where "not found" is a normal outcome?
- [ ] Any raw `Connection` handling that closes directly rather than via `DataSourceUtils`?
- [ ] Any `IN` clause built by string concatenation?
- [ ] Are read-only transactions marked `readOnly = true`, so the driver and ORM can optimise?
- [ ] Is the pool sized against the database's connection limit, not guessed?
- [ ] Batch operations using `batchUpdate` rather than a loop of single updates?

---

## Reading the source yourself

Everything named below is already on your machine, but as compiled jars. Unpack the sources once —
they land in `.spring-sources/`, which is gitignored:

```bash
./tools/fetch-spring-sources.sh spring-jdbc
```

Now you can grep them, which is faster than any IDE search:

```bash
grep -rn "class JdbcTemplate" .spring-sources/
```

**Then walk the path below, in order.** It is not a list of classes to read in full. Each stop names
one method and one thing to notice, and that is all you need from it — most of these classes are
hundreds of lines you can safely ignore.

Do it once with a debugger rather than by reading. Set a breakpoint where the path says to start,
run the lab test for this note, and step through. One pass is worth more than an hour of reading,
because you see the real values.

New to this? [How to read Spring source](reading-the-source.md) is the general method — how to find
the entry point for any feature, and the five shapes it will turn out to be.

<!-- widget:path:jdbc-query -->

**You have understood this when you can say, without looking:** the one branch that decides whether
a statement reuses a connection or takes a new one.

---

## The classes involved

For reference later. The ordered walk is above; this is the same material as a lookup table.

| Class | Role |
|---|---|
| `jdbc.core.JdbcTemplate` | the template method every operation funnels through |
| `jdbc.datasource.DataSourceUtils` | the connection branch, and correct release |
| `jdbc.support.JdbcAccessor` | picks the default exception translator |
| `jdbc.support.SQLExceptionSubclassTranslator` | the Framework 6+ default |
| `jdbc.support.AbstractFallbackSQLExceptionTranslator` | the custom, specific, fallback chain |
| `jdbc.core.simple.DefaultJdbcClient` | the fluent facade |
