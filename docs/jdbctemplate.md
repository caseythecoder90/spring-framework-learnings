# JdbcTemplate and DataSource, end to end

Verified against **Spring Framework 7.0.9 / Spring Boot 4.1.1**. Every claim below is pinned by a
test in [`labs/lab-jdbc`](../labs/lab-jdbc); if a claim and a test ever disagree, the test is right.

First note in the Data track, and deliberately the bottom of the stack. Transactions and Hibernate
both sit on top of the connection handling described here, so this is the layer to understand
first.

---

## The short version

| Assumption | Reality |
|---|---|
| One `JdbcTemplate` call, one connection | Only outside a transaction. Inside, they all share one |
| `queryForObject` returns null when nothing matches | It **throws** |
| Exception translation uses vendor error codes | Not since Framework 6. It uses JDBC 4 subclasses |
| `JdbcTemplate` is the current API | `JdbcClient` has been the one to reach for since 6.1 |
| `?` can take a list for an `IN` clause | It cannot. That is what named parameters are for |

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

## Review checklist

- [ ] Does a method run several statements outside a transaction, taking a connection each time?
- [ ] Any `queryForObject` where "not found" is a normal outcome?
- [ ] Any raw `Connection` handling that closes directly rather than via `DataSourceUtils`?
- [ ] Any `IN` clause built by string concatenation?
- [ ] Are read-only transactions marked `readOnly = true`, so the driver and ORM can optimise?
- [ ] Is the pool sized against the database's connection limit, not guessed?
- [ ] Batch operations using `batchUpdate` rather than a loop of single updates?

---

## The code path

<!-- widget:path:jdbc-query -->

---

## Source map

| Class | Role |
|---|---|
| `jdbc.core.JdbcTemplate` | the template method every operation funnels through |
| `jdbc.datasource.DataSourceUtils` | the connection branch, and correct release |
| `jdbc.support.JdbcAccessor` | picks the default exception translator |
| `jdbc.support.SQLExceptionSubclassTranslator` | the Framework 6+ default |
| `jdbc.support.AbstractFallbackSQLExceptionTranslator` | the custom, specific, fallback chain |
| `jdbc.core.simple.DefaultJdbcClient` | the fluent facade |
