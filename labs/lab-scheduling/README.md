# lab-scheduling

Executable proof for [`docs/scheduling.md`](../../docs/scheduling.md).

```bash
./mvnw -pl labs/lab-scheduling -am test
./mvnw -pl labs/lab-scheduling spring-boot:run -Dspring-boot.run.profiles=demo
```

| Test | Claim it pins |
|---|---|
| `SchedulerWiringTest` | `@EnableScheduling` registers one post-processor under a known name; each `@Scheduled` method becomes a `CronTask` / `FixedDelayTask` / `FixedRateTask`; the processor is `LOWEST_PRECEDENCE`, so it sees the proxy |
| `BootSchedulerDefaultsTest` | Boot creates no `TaskScheduler` until scheduling is enabled, then gives you a **one-thread** pool named `scheduling-`; your own bean replaces it |
| `SingleThreadStarvationTest` | One blocked job stops every other `@Scheduled` method — latch-driven, no timing luck involved |
| `FixedRateVsFixedDelayTest` | With 150ms of work on a 100ms interval, `fixedRate` runs back-to-back while `fixedDelay` adds the gap on top |
| `ScheduledErrorHandlingTest` | A method that throws every run keeps its schedule; the failure is visible through `Task.getLastExecutionOutcome()` |
| `SchedulerRoutingTest` | An unqualified job falls back to the bean named `taskScheduler`; `@Scheduled(scheduler = "...")` routes to its own pool |

The demo profile runs a 500ms heartbeat next to a four-second job on a pool of one. The heartbeat
stalls for four seconds at a time — that is the starvation test, visible in a log.
