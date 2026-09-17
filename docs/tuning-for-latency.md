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

Staffix ships almost every validation **off**, and each one documents its own cost. This is the opposite of most
engines and it is the single easiest way to give the performance back: enable a check and you pay for it on every
message, forever.

Off by default, each costing "a slight impact on performance" per its javadoc: `validateRequiredFields`,
`validateFieldsOutOfOrder`, `validateDuplicateTags`, `validateCompId`, `validateBeginString`,
`detectGarbledMessages`, `maxSendingTime`.

On by default: `validateChecksum`, `validateFieldsHaveValues`, `allowUndefinedTagsForMessage`.

`detectGarbledMessages` is the most expensive of them: it checks the position of every header field of every
received message.

Turn on what your counterparty relationship actually requires, and no more. See
[Configuring a session](configuring-sessions.md#validation).

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

### The sanitizer

The dictionary sanitizer is a Maven plugin that removes what nothing references: components no message uses, and
fields no message, component or group refers to.

```xml
<plugin>
    <groupId>org.lolaf.staffix</groupId>
    <artifactId>staffix-fix-dictionary-sanitizer-maven-plugin</artifactId>
    <version>${staffix.version}</version>
    <executions>
        <execution>
            <phase>generate-sources</phase>
            <goals><goal>sanitize</goal></goals>
            <configuration>
                <inputFile>${project.basedir}/src/main/dictionaries/MYFIX44.xml</inputFile>
                <outputFile>${project.basedir}/src/main/dictionaries/MYFIX44-sanitized.xml</outputFile>
            </configuration>
        </execution>
    </executions>
</plugin>
```

Point the [encoders generator](fix-versions-and-dictionaries.md#generating-a-package-from-your-own-dictionary) at the
sanitized file. It reports what it removed, so you can see what the cut bought.

Two options are worth knowing:

- **`keepFields`**: field names to keep even when nothing references them. From FIX 5.0 the header and trailer are
  empty (the session layer is FIXT.1.1's), so a 5.0+ dictionary references none of its own session fields and they
  would all be removed. This is how you keep them.
- **`sanitizeMsgTypeField`**: prunes MsgType(35)'s enumerated values down to the messages the dictionary actually
  defines. Leave it off when the session layer lives in FIXT.1.1, or the value list stops agreeing with the session
  messages that are still legal on the wire.

### Better still: do not generate it in the first place

Sanitizing removes what is unreferenced. Cutting the dictionary at the source removes what you are never going to
trade. If you generate from an Orchestra repository, a message list does that. `fix-latest` ships 93 messages out of
everything the standard defines for exactly this reason, and narrowing the list further is one text file and one
command. See
[FIX versions and dictionaries](fix-versions-and-dictionaries.md#fix-latest).

The order to apply them in is: cut the message list to what you trade, then sanitize what that leaves.

---

## 5. Map only the fields you use

A decoder parses what you map and skips the rest. A field nobody maps is never converted, never allocated and never
stored. This is not an optimisation to apply later; it is the default behaviour, and the way to lose it is to map
fields "just in case".

---

## 6. Choose an object strategy per field

For fields that must become objects, pick how each one is produced, with
`FixFieldsDecoderMapper.ObjectInstanceStrategy`:

| strategy | allocation | constraint |
|----------|-----------|------------|
| `NEW_INSTANCE` | one object per decoded value | none; safe to retain and to hand to another thread |
| `CACHED` | only on a value not seen before | **cache is unbounded**, so small value universes only |
| `THREAD_LOCAL` | none | reference dies at the next `THREAD_LOCAL` field; never hand it to another thread |

```java
mapper.mapStringField(Symbol.get(), this::setSymbol, null, CACHED)
      .mapStringField(QuoteReqID.get(), this::setQuoteReqId, null, THREAD_LOCAL);
```

`CACHED` suits symbols, currencies, exchanges and enumerations: values that repeat forever. **Using it on a
high-cardinality field leaks memory**: the cache is bound to the session and never evicts, so an order id or a
timestamp will grow it without limit.

`THREAD_LOCAL` gives zero allocation with the tightest constraint: the value is valid inside the setter, and across
the message only if it is the only `THREAD_LOCAL` field on that decoder. Read it, copy what you need, do not store
the reference.

`UUID` supports `NEW_INSTANCE` and `THREAD_LOCAL`; `CACHED` throws.

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
