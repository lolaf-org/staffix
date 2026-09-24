# Tuning for latency

The README quotes a round trip of 19.5 µs at stock settings and 11.2 µs busy-spinning. This guide is what turns the
first number into the second, and what each step costs you.

Work down it in order. The first two items are worth more than everything below them combined.

---

## 1. JVM flags

Without these, the concurrent structures do not get their cache-line padding and the zero-allocation serdes fall back
to allocating. Staffix logs a warning at startup for the ones it can detect.

```
--enable-native-access=ALL-UNNAMED
-XX:-RestrictContended
-XX:ContendedPaddingWidth=64
--add-opens java.base/jdk.internal.misc=ALL-UNNAMED
```

The first three make `@Contended` actually pad. The `--add-opens` enables the thread-local serdes described below;
without it they still work, but allocate a fresh object per field and say so once at startup.

---

## 2. Choose where the CPU goes

This one setting is the difference between the two tables in the README.

A **select strategy** decides how the I/O threads wait for the socket:

```java
IOWorkersGroupSettings.builder()
        .ioThreadGroup(IOWorkersGroupSettings.IOThreadGroup.builder()
                .selectStrategy(new IdleStrategySelectStrategy(BusySpinIdleStrategy.getInstance()))
                .build())
        .build().newInstance()
```

| strategy | behaviour | when |
|----------|-----------|------|
| `WakeupSelectStrategy` | blocking selector, woken on work | default; costs no CPU while idle |
| `IdleStrategySelectStrategy` + `BusySpinIdleStrategy` | never sleeps | lowest latency, burns a core per I/O thread |
| `IdleStrategySelectStrategy` + `BackoffIdleStrategy` | spins, then yields, then parks | a middle position when you cannot dedicate cores |

An **idle strategy** does the same job for the message executor threads
(`MessageExecutorSettings.idleStrategy`, default `WaitNotifyIdleStrategy`): `BusySpinIdleStrategy`,
`YieldingIdleStrategy`, `BackoffIdleStrategy`, `TimerSlackAwareBackoffIdleStrategy`, `WaitNotifyIdleStrategy`,
`TimedWaitNotifyIdleStrategy`.

**Busy-spinning is not free.** It buys latency by occupying a core continuously. On a machine with fewer cores than
spinning threads it makes things worse, not better.

If you go this route, pin the spinning threads to cores. Both the I/O thread group and `MessageExecutorSettings`
take a `ThreadFactory` (default `FastThreadLocalThread::new`), which is the hook for it; the examples do this with
a small affinity-setting subclass of their own in
[`FixExamplesBase`](../examples/examples-core/src/main/java/org/lolaf/staffix/examples/FixExamplesBase.java);
there is no affinity thread factory in the library itself.

---

## 3. Do not turn on validation you do not need

Staffix ships almost every validation **off**, the opposite of most engines: each check you enable is paid on every
message. `detectGarbledMessages` is the most expensive, checking the position of every header field. Turn on what your
counterparty requires and no more; the list is in [Configuring a session](configuring-sessions.md#validation).

---

## 4. Ship a dictionary that holds only what you use

A dictionary is not just documentation the generator reads and forgets. **Its size follows the engine into every
running session**, because the parsing machinery is indexed by it.

Each field in a dictionary is assigned a **dense index starting at zero**, and that index (not the FIX tag) is what
addresses the collections the parser works with: received-field and required-field tracking, group member ordering,
the per-message field metadata. Those structures are sized by the dictionary's index space. The field registry itself
is an `Int2ObjectHashMap` sized to the field count and deliberately kept sparse (load factor 0.1) so lookups stay
fast, which means its memory is roughly ten slots per field you defined.

The consequence is direct: **a dictionary carrying 6,000 fields you never send makes every one of those structures
larger than it needs to be**: more memory, more cache lines touched, less of the hot set resident. FIX Latest defines
over 6,000 fields; a real counterparty relationship uses a small fraction of them.

### Cutting it down

Two cuts, in this order, both covered in
[FIX versions and dictionaries](fix-versions-and-dictionaries.md):

1. **Generate only the messages you trade.** With an Orchestra cut, a message list does this; `fix-latest` ships 93
   messages out of the whole standard for exactly this reason. See [FIX Latest](fix-versions-and-dictionaries.md#fix-latest).
2. **Sanitize what is left.** The [sanitizer](fix-versions-and-dictionaries.md#sanitizing-a-dictionary) plugin removes
   components and fields nothing references, and reports what it removed.

---

## 5. Map only the fields you use

A decoder parses what you map and skips the rest. A field nobody maps is never converted, never allocated and never
stored. This is not an optimisation to apply later; it is the default behaviour, and the way to lose it is to map
fields "just in case".

---

## 6. Choose an object strategy per field

For fields that must become objects, `ObjectInstanceStrategy` decides the allocation (table in
[Decoding a message](decoding-messages.md#what-a-decoded-object-costs)):

- **`CACHED`** for values that repeat forever: symbols, currencies, exchanges, enumerations. **On a high-cardinality
  field it leaks memory**: the cache belongs to the session and never evicts, so an order id grows it without limit.
- **`THREAD_LOCAL`** for zero allocation, with the tightest constraint: valid inside the setter, and across the
  message only if it is the decoder's only `THREAD_LOCAL` field. Copy what you need, never store the reference.
- **`NEW_INSTANCE`**, the default, for anything kept or handed to another thread.

---

## 7. Reuse encoders

`session.newEncoder(X.class).asReusable()` gives an encoder you fill in and send repeatedly instead of building a new
one per message. Inside a decoder's `onDecoded` you are on the session's I/O thread and the message goes straight to
the socket, which is what makes reuse safe there, and the quickstart's acceptor does exactly this.

Sessions also expose a pool via `newEncodersPool()` for the paths where a single reusable instance will not do.

---

## 8. Keep persistence and logging off the message path

A store or logger that does I/O inline puts that I/O inside your round trip. Wrap it in the async decorator, which
hands the work to a Chronicle Queue and returns. See [Stores and loggers](stores-and-loggers.md).

The benchmark numbers in the README were produced with an in-memory store and logging off, so they measure the
protocol path alone. Turning on a real store is what the async wrapper exists to keep out of the round trip.

---

## 9. Warm up before you measure

`staffix-jvm-warmup` exists because the first thousand messages through a cold JIT are not representative of
anything. Warm the path before you trust a number, and before the market opens.

---

## Measuring your own changes

The benchmarks are in [`benchmarks`](../benchmarks), including the round-trip harness that produced the README's
figures:

```bash
mvn clean install -pl benchmarks -am
java -jar benchmarks/target/benchmarks.jar
```

Two habits worth keeping. Measure allocation as well as time: JMH's GC profiler is what shows a change that trades
0.4 bytes per message for a microsecond. And measure the tail, not the mean: the difference between Staffix's stock
and busy-spin modes is visible in the mean, but it is *dramatic* at the maximum, and the maximum is what a trading
system feels.
