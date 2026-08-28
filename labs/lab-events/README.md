# lab-events

Executable proof for [`docs/events.md`](../../docs/events.md).

```bash
./mvnw -pl labs/lab-events -am test
./mvnw -pl labs/lab-events spring-boot:run -Dspring-boot.run.profiles=demo
```

| Test | Claim it pins |
|---|---|
| `SynchronousPublishTest` | Listeners run on the publishing thread; `publishEvent` returns only after the last one; a listener exception reaches the publisher and skips the listeners behind it; a plain record is wrapped in `PayloadApplicationEvent`; the multicaster has no executor |
| `ListenerRegistrationTest` | `EventListenerMethodProcessor` adapts annotated methods; `@Order` sequences them; a SpEL `condition` gates invocation; a returned value is republished, and a returned `Collection` fans out |
| `AsyncListenerTest` | `@Async` moves the listener off the publishing thread, and its exception goes to the `AsyncUncaughtExceptionHandler` instead of the caller |
| `TransactionalEventListenerTest` | `AFTER_COMMIT` runs after the writing method returns; a rollback skips it and fires `AFTER_ROLLBACK`; with **no transaction it is silently skipped** unless `fallbackExecution = true` |

The demo profile prints the thread name at every hop, and prints `placeOrder END` after every
listener has finished — the whole point, in four log lines.

`spring-boot-starter-jdbc` and H2 are here only so the transactional listeners have a real
transaction to synchronize on. Nothing is ever written to the database.
