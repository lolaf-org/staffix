# `jvm-warmup` — get the engine hot before the market does

A cold JVM is a slow JVM. Java starts interpreted, and only after a method has been executed enough times does the
JIT compile it — C1 first, then C2 with the profile C1 collected. The first few thousand messages through a freshly
started FIX engine therefore run one to two orders of magnitude slower than the same code will run a minute later,
and they run with an empty branch profile, cold inline caches, cold data caches and unfaulted pages.

That is fine for a benchmark. It is not fine for the exact moment it happens in production.

## Why this module exists

Trading systems are deployed when the market is closed — Saturday morning, or after the close the night before. The
process then sits idle until the open. The very first messages it ever handles are the open: the highest-volume,
most latency-sensitive burst of the week, hitting a JVM that has never executed the parsing loop, never compiled the
encoders, never taken the session state machine's branches.

The point of a warmup is to move that cost into the weekend. Drive realistic traffic through the real engine
straight after deployment, and by the time the first genuine order arrives, C2 has compiled the message path,
inlined it, and specialised it on real branch data. The open is served by compiled code from the first message.

This is why the warmup runs a **full loopback FIX session** rather than calling methods in a loop. Everything on the
message path is exercised end to end and in the order it is used in production: NIO read → parse → decode →
application callback → encode → session sequencing → store → logger → NIO write.

## What it does

`JvmWarmup` starts an in-process acceptor and initiator on loopback and echoes a synthetic `JVMWarmup` message
between them for a configurable duration, then tears everything down and reports.

The message is deliberate. It is defined in [`JVMWarmup-dictionary.xml`](src/main/resources/JVMWarmup-dictionary.xml)
under msgtype `Z0` (unassigned in FIX 4.4), and carries **one field of every `FieldType` the codec knows** — every
numeric, string, enum, data and the four timestamp/date/time flavours — plus a repeating group. So a single
round trip walks every branch of the parser and every encoder specialisation rather than the two or three a real
message would.

The two sides deliberately differ in how they map fields:

- the **acceptor** registers lambda-`Consumer` mappings,
- the **initiator** registers VarHandle mappings, with the repeating group VarHandle-only,

so both decoder dispatch styles get compiled. Field values rotate with a call counter, so equality-cached and
field-reset paths are both taken.

## Using it

`run` returns a `CompletableFuture<JvmWarmupResult>` immediately and does the work on a daemon thread. The usual
shape is to block until warm, because that is exactly the sequencing you want at startup:

```java
JvmWarmupResult result = JvmWarmup.run(JvmWarmupOptions.builder()
                .warmupDuration(Duration.ofMinutes(10))
                .fixEngineBuilder(myEngineBuilder)
                .build())
        .join();
```

The future completes **after** the warmup has stopped its engines and released the loopback port, so once `join()`
returns you can bind the real sessions. Everything else is the ordinary `CompletableFuture` API — `thenAccept` to be
notified without blocking, `exceptionally` / `handle` for failures, `get(timeout, unit)` or `orTimeout` if you want a
bound on a warmup that never finishes:

```java
JvmWarmup.run(options)
        .thenAccept(r -> log.info("warmup done: sent={}, duration={}", r.getMessagesSent(), r.getActualDuration()))
        .exceptionally(cause -> { log.error("warmup failed", cause); return null; });
```

### Cancelling shortens the run, it does not abandon it

`cancel(…)` on the returned future is the one place the behaviour departs from `CompletableFuture`. It asks the
warmup to stop early; the run then winds down, tears its engines down as usual, and the future **completes
normally** with a result whose `isCancelled()` is `true` and whose counters hold what was exchanged before the stop.
A warmup cut short still warmed everything it ran through, and you usually want to know how far it got.

Consequently `cancel` returns `false` and `isCancelled()` stays `false` — the future was never cancelled in the
`Future` sense, so `join()` gives you a result instead of throwing `CancellationException`.

There is also a `main` for a standalone warm-up or a smoke test, taking an ISO-8601 duration
(default `PT60S`):

```bash
java -cp … org.lolaf.staffix.jvmwarmup.Main PT2M
```

## Options

All of [`JvmWarmupOptions`](src/main/java/org/lolaf/staffix/jvmwarmup/JvmWarmupOptions.java) has a default; the
zero-arg builder is a usable warmup.

| option | default | what it is for |
|--------|---------|----------------|
| `warmupDuration` | 5 min | how long traffic flows before tear-down |
| `throttling` | 10 ms | inter-message interval, i.e. ≈100 msg/s. `Duration.ZERO` sends as fast as the session flushes |
| `fixEngineBuilder` | none | **the one worth setting** — see below |
| `lowLatency` | `false` | `false` uses wait/notify idling so the warmup does not pin a core; `true` warms the busy-spin path instead |
| `fixt` | `false` | run the sessions over FIXT 1.1 instead of FIX 4.4, to warm the FIXT codec path |
| `port` | 7099 | loopback port the pair uses |
| `sender` / `target` | `JVM-WARMUP-…` | CompIDs, visible in logs |
| `acceptorInstanceId` / `initiatorInstanceId` | `jvm-warmup-…` | instance ids, visible in logs and JMX |

### Pass your own `fixEngineBuilder`

Without it the warmup uses a no-op memory store and a disabled logger — it warms the engine, but not the parts of
your deployment that sit on the message path.

Give it your real engine builder and it clones the store, logger and plugin *settings*, instantiates fresh copies of
those same types, and stands up **one session pair per configured store, logger and plugin** so each one is actually
driven. Your JDBC store's prepared-statement path, your file logger's buffer flush, your monitoring plugin's
callbacks — all compiled before the open, instead of at it.

The clone gets its own applications, sessions and admin API (JMX export is disabled for the warmup), so it never
touches the engine you will actually trade on.

### `lowLatency` should match production

The default is the good-citizen setting: wait/notify idling, so a warmup running at startup does not burn a core.
If production runs busy-spin idling and busy-spin select strategies, set `lowLatency(true)` — otherwise the paths
you warmed are not the paths you will run.

## Where it fits in a deployment

```
Saturday  deploy, start the process
          └─ JvmWarmup.run(...) for 10-30 minutes   ← C2 compiles the message path
          └─ warmup tears itself down, port released
          then  start the real acceptor / initiators, idle over the weekend
Monday    open — first real message is served by compiled code
```

Two things to keep in mind when scheduling it:

- **Run it before binding real sessions**, and let it finish. Blocking on the future is exactly this — the warmup
  releases its loopback port and stops its engine before the future completes.
- **Longer is not free but is usually right.** C2 compilation is driven by invocation counts, not wall time; at the
  default 100 msg/s a 10-minute warmup is ~60k round trips, comfortably past every default JIT threshold. Raising
  the rate (`throttling(Duration.ZERO)`) gets there faster at the cost of saturating a core.

## What it does *not* do

- **It does not warm your `FixApplication`.** The warmup drives its own applications, so your business logic — your
  decoders, your order book, your risk checks — is not compiled by it. It warms the engine underneath.
- **It does not survive a restart.** JIT state dies with the process; the warmup has to run on every start.
- **It is not a substitute for the JVM flags.** See the [examples README](../examples/README.md#jvm-flags) — a warmup
  cannot make `@Contended` pad if `-XX:-RestrictContended` is missing.

## Seeing it run

The [`advanced-monitoring` example](../examples/advanced-monitoring) takes a `-jw` flag that runs a 2-minute warmup
before starting, which is the easiest way to watch the difference on the latency dashboard: run it once with `-jw`
and once without, and compare the first minute of the read/write latency heatmaps.
