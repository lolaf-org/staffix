# Staffix

**Streaming API for FIX** — a FIX engine for Java that treats latency as a correctness property.

Staffix is a full FIX protocol implementation for Java 11 and up: session layer, type-safe message encoders for every
FIX version from 4.2 to FIX Latest, persistence, logging, monitoring and administration. It is built for the systems
where a message arriving 30 microseconds late is a message that arrived wrong.

- **19.5 µs round trip**, against QuickFIX/J's 37.1 on identical settings, and 11.2 µs busy-spinning
  ([measured](#measured))
- **Under one byte allocated per message**, against QuickFIX/J's sixteen kilobytes ([allocation](#allocation))
- **A jar, not a platform** — in your process, on your threads, with no media driver and no second process to
  operate ([compared to Artio](#compared-to-artio))

---

## What you get

**Protocol** — FIX 4.2, 4.3, 4.4, 5.0, 5.0SP1, 5.0SP2, FIX Latest, and the FIXT.1.1 session layer. Type-safe
generated encoders, message-type and field registries, per-message field-order metadata.

**Low latency** — a 19.5 µs round trip (localhost on a 5ghz CPU), under one byte allocated per message and no GC pause
in the run, [measured](#measured) against QuickFIX/J and Artio on one harness. Persistence and logging are kept off that
path rather than traded against it: a file-based message store and the message loggers run behind an off-heap,
memory-mapped Chronicle Queue handoff, so the session thread neither waits for the I/O nor allocates to hand it
over.

**Session layer** — initiator and acceptor, sequence numbers, resend handling, logon/logout, heartbeats, test requests,
gap fill, and the validation switches to tune strictness against latency.

**Persistence** — message stores in memory, on file, or over JDBC, and any of them behind the Chronicle Queue-backed
async wrapper that takes the I/O off the session thread. All implementations are verified against one shared contract
test suite, so a store you write yourself is held to the same standard as the ones that ship.

**Logging** — SLF4J, file, demux and OTLP message loggers, and the same async wrapper for any of them.

**Configuration** — session settings in memory or in YAML files, with a JSON schema generated from the model at build
time and shipped in the jar, so your editor completes and validates the file.

**Administration** — an `AdminApi` carrying the whole operational surface: log a session on or off, reset sequence
state, set or read either side's sequence numbers, reload a settings store without a restart, and be notified as
sessions come and go. It is deliberately independent of how it reaches the engine — `AdminApiExporter` is the SPI,
and JMX is the exporter that ships, so you are not forced onto a management protocol you do not use. See
[Runtime administration](docs/runtime-administration.md).

**Monitoring** — Micrometer metrics with configurable timers for latency, and OpenTelemetry tracing with W3C
trace propagation, both exportable over OTLP, with Grafana dashboards included. Both can be wrapped to run its callbacks
on their own threads or to sample them, because metrics are not worth latency. See [Monitoring](docs/monitoring.md).

**JVM warmup** — [`jvm-warmup`](jvm-warmup) runs a full loopback FIX session against a synthetic message carrying
one field of every type the codec knows, so C2 has compiled and specialised the whole message path — parser,
encoders, session state machine, and your own store, logger and plugins — before the first real order arrives,
rather than the open being served interpreted.

**Identifiers** — UUID v7 and v4 generators and a Snowflake id generator, with an allocation-free path from generation
to the encoder for v7 and Snowflake, and the serdes to read them back off the wire the same way.

**Integration** — a plain application factory or a Spring Boot starter.

**Extension points** — SPIs for messages stores, loggers, session-settings stores, admin exporters and **session
plugins** are each an
interface plus a settings object, selected by instance id. A session plugin attaches to every message of a session
from the outside: measure it, audit it, or add a field to it on the way out without the application knowing. See
[Session plugins](docs/session-plugins.md).

**Runtime** — Java 11 source and target, running on JDK 11 through 25. Low-level memory access is split into
JDK-range-specific modules so the right one is selected at runtime rather than the codebase being pinned to one JDK.

---

## Getting started

**Staffix is not on Maven Central yet.** Until the first release, build it from source with `mvn clean install` at the
repository root; the coordinates below then resolve from your local repository.

The quickest look at a working session is the quickstart — an acceptor and an initiator in one process, a QuoteRequest
sent and a Quote returned:

```bash
mvn compile exec:java -pl examples/quickstart \
    -Dexec.mainClass=org.lolaf.staffix.examples.quickstart.QuickstartExample
```

[`QuickstartExample`](examples/quickstart/src/main/java/org/lolaf/staffix/examples/quickstart/QuickstartExample.java)
is the whole of what a first Staffix program needs, with nothing hidden in a base class. The other worked examples —
a session plugin adding a field the application never wrote, a trading example, a market data streamer, a quote
request client, YAML session settings, a Spring Boot application and one wired up with full monitoring — are indexed
in [`examples/`](examples/README.md).

### In your own project

The dependencies for a first application — the engine, the FIX version you speak, and an implementation of each
pluggable part:

```xml

<dependency>
    <groupId>org.lolaf.staffix</groupId>
    <artifactId>staffix-impl</artifactId>
    <version>${staffix.version}</version>
</dependency>
<dependency>
    <groupId>org.lolaf.staffix</groupId>
    <artifactId>staffix-fix-44</artifactId>
    <version>${staffix.version}</version>
</dependency>
<dependency>
    <groupId>org.lolaf.staffix</groupId>
    <artifactId>staffix-application-factory-simple</artifactId>
    <version>${staffix.version}</version>
</dependency>
<dependency>
    <groupId>org.lolaf.staffix</groupId>
    <artifactId>staffix-messages-store-memory-impl</artifactId>
    <version>${staffix.version}</version>
</dependency>
<dependency>
    <groupId>org.lolaf.staffix</groupId>
    <artifactId>staffix-sessions-settings-store-memory-impl</artifactId>
    <version>${staffix.version}</version>
</dependency>
<dependency>
    <groupId>org.lolaf.staffix</groupId>
    <artifactId>staffix-messages-logger-slf4j-impl</artifactId>
    <version>${staffix.version}</version>
</dependency>
```

Then build an engine, hand it an application per session, and start an acceptor or an initiator from it.
[`QuickstartExample`](examples/quickstart/src/main/java/org/lolaf/staffix/examples/quickstart/QuickstartExample.java)
is that program, and it is the one the command above runs.

### Where the rest of the documentation is

**[`docs/`](docs/README.md) is the index**, and everything else is under it: eleven guides, each answering one
question. [Threading model](docs/threading-model.md) and [configuring a session](docs/configuring-sessions.md)
are the two to read first — which thread runs your code, and how to declare a session.
[Tuning for latency](docs/tuning-for-latency.md) is how you get from the stock numbers [below](#measured) to the
busy-spin ones. The others cover [stores and loggers](docs/stores-and-loggers.md),
[FIX versions and dictionaries](docs/fix-versions-and-dictionaries.md),
[session plugins](docs/session-plugins.md), [identifiers](docs/efficient-identifiers-usage.md),
[monitoring](docs/monitoring.md), [network monitoring](docs/network-monitoring.md),
[runtime administration](docs/runtime-administration.md) and [Spring Boot](docs/spring-boot.md).

Beside it: [`examples/`](examples/README.md), every runnable example, smallest first.

---

## Measured

A QuoteRequest sent from an initiator to an acceptor over loopback TCP, and the response awaited. Same JMH harness, same
JVM, same fork settings, same machine, all three engines started by the same code:
[`FixEngineRTTBenchmark`](benchmarks/src/main/java/org/lolaf/staffix/benchmarks/FixEngineRTTBenchmark.java).

**Every engine runs with an in-memory store and message logging switched off.** This measures the protocol path — parse,
dispatch, encode, write — and nothing else. It is not a measurement of any engine's persistence, and no engine here is
doing durable work. Turn a real store on and all three numbers move; what the async wrapper described
[below](#how-the-latency-is-achieved) exists to do is keep that movement out of the round trip.

Round-trip latency, microseconds, **stock settings** — the like-for-like comparison, since this is the only
configuration all three engines support:

| engine           | mean      | p50       | p90       | p99       | p99.9     | max     |
|------------------|-----------|-----------|-----------|-----------|-----------|---------|
| **Staffix**      | **19.48** | **19.14** | **21.76** | **25.02** | **33.22** | **243** |
| QuickFIX/J 3.0.1 | 37.13     | 36.54     | 38.78     | 48.13     | 79.62     | 1858    |
| Artio 0.181      | 40.38     | 29.70     | 47.49     | 203.01    | 315.39    | 7881    |

Staffix's 99th percentile, 25.02 µs, is faster than QuickFIX/J's median. Read the last two columns with more caution
than the rest: a p99.9 and a maximum are the few worst observations of one run, they move between runs, and what they
mostly report is what the JVM did that afternoon rather than what the engine does.

Staffix also runs a busy-spin I/O mode. QuickFIX/J has no equivalent — the benchmark harness rejects any non-stock
setting for it — so this is not a comparison with QuickFIX/J, only a statement of what the engine reaches when told to
spend CPU on latency:

| engine                 | mean      | p50       | p90       | p99       | p99.9     | max     |
|------------------------|-----------|-----------|-----------|-----------|-----------|---------|
| **Staffix**, busy-spin | **11.19** | **10.99** | **11.57** | **13.89** | **15.46** | **231** |
| Artio, low-latency     | 29.75     | 28.67     | 32.96     | 37.12     | 110.34    | 5849    |

What CPU buys is not only the median but the shape of the distribution: busy-spinning halves the round trip and pulls
p99.9 from 33 µs to 15 µs, so the slowest thousandth of round trips lands within 5 µs of the median rather than 14 µs
above it. The maxima of the two Staffix configurations are within a few percent of each other, which is the point made
above about single observations. Artio in its own low-latency mode still reaches 5.8 ms, though it is yielding rather
than busy-spinning there, which is a different bargain with the CPU.

### Allocation

The same run, measured by JMH's GC profiler. Bytes allocated per round trip, steady state:

| engine                  | bytes / round trip | allocation rate | GC pauses during the run |
|-------------------------|--------------------|-----------------|--------------------------|
| **Staffix** (busy-spin) | **0.2 – 0.4**      | ~0              | none                     |
| **Staffix** (stock)     | **0.4 – 0.7**      | ~0              | none                     |
| Artio                   | 0.6 – 2.2          | ~0              | none                     |
| QuickFIX/J              | **16,000**         | 399 MB/s        | 2 collections, 4 ms      |

Staffix allocates **less than one byte per round trip** — not one object per message, but a handful of objects across
the whole run, amortised to under a byte. QuickFIX/J allocates sixteen kilobytes for the same work: a new object graph
per message, parsed eagerly into strings, at 399 MB of garbage per second on one session. That is roughly four orders of
magnitude, and it is the difference between a latency profile with no GC in it and one where the collector is a
participant.

Be clear about what this table does not say: **Artio allocates essentially nothing either.** On memory it and Staffix
are peers; QuickFIX/J is the outlier. If the objection to your current engine is garbage, both of these solve it.

<sub>JMH 1.37, JDK 21.0.12, 1 fork, 2 warmup + 3 measurement iterations of 10 s, single thread, single session, both
ends on one
host over loopback. Latency figures are sample mode; allocation figures agree across sample, average and throughput
modes. Store and logging configuration per engine: Staffix an in-memory store holding zero entries, QuickFIX/J a
`NoopStoreFactory`, Artio with inbound and outbound message logging disabled. Single-shot mode allocation is excluded:
it measures one cold invocation and is dominated by one-time setup for every engine. Raw results:
[
`jmh-result-FixEngineRTTBenchmark-2026-08-13.json`](benchmarks/results/jmh-result-FixEngineRTTBenchmark-2026-08-13.json).
This is a benchmark, not a production measurement: one message type, one session, no contention, no network. Run it
yourself —
`mvn clean install -pl benchmarks -am && java -jar benchmarks/target/benchmarks.jar`.</sub>

**Every run is kept.** [`benchmarks/results/`](benchmarks/results) holds the raw JMH JSON for each benchmark and each
date it was run — the round trips above, the id generators, the serdes, the clock — so a number quoted anywhere in this
documentation can be traced to the file it came from, and successive runs can be compared rather than taken on trust.
Drop any of those files into [jmh.morethan.io](https://jmh.morethan.io) to browse and chart it, or several at once to
diff them.

The round trip is the headline, but the serde benchmarks are the ones to read when you are choosing between the forms
a field can take, because that choice is yours to make per field and the cost of each is measured rather than argued:

| benchmark                                                                                               | what it puts side by side                                                                                                                                         |
|---------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| [`IntSerDeBenchmark`](benchmarks/src/main/java/org/lolaf/staffix/benchmarks/IntSerDeBenchmark.java)     | serializing into a caller's buffer against allocating a fresh array, and against the per-session cache, over integer widths from one digit to `Integer.MIN_VALUE` |
| [`LongSerDeBenchmark`](benchmarks/src/main/java/org/lolaf/staffix/benchmarks/LongSerDeBenchmark.java)   | decoding 64-bit values, signed against unsigned, up to `Long.MAX_VALUE`                                                                                           |
| [`FloatSerDeBenchmark`](benchmarks/src/main/java/org/lolaf/staffix/benchmarks/FloatSerDeBenchmark.java) | `DecimalFloat` against `double` and against `BigDecimal`, both directions — the decimal question every FIX price field asks                                       |

What they show is the shape of the argument this README makes: decoding an int is a few nanoseconds and no garbage,
`DecimalFloat` costs a fraction of `BigDecimal` and allocates nothing where `BigDecimal` allocates on every value, and
writing into a buffer you already own beats handing back a new array. Numbers per value width, per direction and per
JDK are in the result files rather than quoted here, because that is exactly the kind of figure that goes stale.

---

## How the latency is achieved

Not by one trick. By a set of rules applied everywhere on the message path.

**Nothing is allocated on the happy path.** The parser compares hashes instead of materialising strings, decodes fields
lazily — `SendingTime(52)` is only parsed when a check actually needs it — and builds strings, exceptions and
collections only on the reject branch. Encoders come from a pool and are reusable across messages.

**Field values are decoded from bytes, never through a `String`.** This is where a FIX engine spends its parsing time,
so Staffix does not use the JDK's converters — not because they are slow, but because of what they require:
`Integer.parseInt` and `Double.parseDouble` take a `String`, and on a wire protocol that means allocating one before you
can begin. Staffix's serdes read the byte buffer directly, with length-specialised paths for the integer widths a FIX
message actually contains, and decimals decoded into a `DecimalFloat` value rather than a `BigDecimal` object graph. The
`String` that the JDK route makes mandatory never exists.

**Every type on the wire has its own serde, both directions.** Not one generic converter with a type switch, but
a dedicated path for each thing a FIX field can hold: `int` and `long` with length-specialised variants and
signed and unsigned forms, `double`, `boolean`, `char` and raw byte arrays; `DecimalFloat` and `BigDecimal` for
prices; `String` and `UUID`; and the six temporal types the protocol actually uses — UTC timestamp, date and time,
the two timezone-qualified forms, and local market date — which are the fiddliest fields in FIX to parse and the
easiest to parse slowly. Every one is a static method, so there is no serde instance to allocate and no virtual
call to dispatch through. Encoding writes into a buffer you already own, `serialize(value, dst, size, offset)`,
rather than handing back a fresh array — and a timestamp you do not need as an object decodes straight to a
primitive `long` of epoch nanoseconds, so the object is never born at all.

**And where a value must become an object, you choose what it costs.** Each of the object-valued serdes comes in three
forms, picked per field:

- **plain** — a fresh object per message, the ordinary behaviour;
- **cached** — a per-session content-hash map, so a repeated value allocates once and is a lookup thereafter. FIX fields
  are full of values that repeat forever: symbols, CompIDs, account and currency codes;
- **thread-local** — a reused instance whose contents are rewritten in place through `Unsafe`, allocating nothing at
  all. The returned value is valid only within the processing thread's scope, which is the trade you are making. Without
  that flag it degrades to allocating normally and logs a warning once, rather than failing.

`String` and `DecimalFloat` have all three forms; `UUID` has plain and thread-local. That is the pattern the whole
engine follows: the fast path is available, its cost is stated, and nothing silently does the expensive thing.

**The identifiers you mint are on that path too.** Every order needs a `ClOrdID`, and the obvious
`UUID.randomUUID().toString()` costs a contended `SecureRandom`, four allocations and a 36-character string per message
— application code, but on the engine's hot path, and pointless to optimise everything around it. Staffix ships the
generators: `UUIDsGenerator` for time-ordered v7 or random v4 UUIDs, and `SnowflakeId` for 64-bit ids that are a
primitive. A v7 id costs **20 ns and no allocation**, a Snowflake id **19 ns and no allocation** — thread-local forms
written into a reused instance and handed straight to the encoder, so the id reaches the wire without an object being
born. Against `UUID.randomUUID()`'s 111 ns and 128 bytes, measured on the machine and in the run that produced the
round trips above. A v4 id keeps its `SecureRandom` floor, which is the JDK's, not ours. See
[Efficient identifier usage](docs/efficient-identifiers-usage.md).

**Optional behaviour costs nothing when it is off.** The established idiom is a strategy swapped once at construction,
not a branch tested per field. Checksum calculation, garbled-message detection and field processing each have an
`Active…`/`Void…` pair chosen from the session settings; the void implementation does nothing, so a disabled feature is
not a branch on the parsing loop — it is absent from it. Every validation flag is off by default and documents its cost
in its own javadoc.

**The plumbing is lock-free.** Staffix sits on two libraries built for it and shipped with it: `ringos`, a set of
lock-free MPMC/SPSC ring buffers with selectable idle strategies (busy-spin, backoff, yield, wait-notify) and a
hashed-wheel timer, and `betty`, an NIO transport with pluggable select strategies. Publishing to a ring buffer goes
through an event translator so nothing is allocated to enqueue.

**False sharing is designed out.** `@Contended` padding on the concurrent structures, with the build configured for
`-XX:ContendedPaddingWidth=64 -XX:-RestrictContended`.

**You choose where the CPU goes.** The same engine runs on a blocking selector or a busy-spin loop — that is the
difference between the two tables above, and it is one setting.

**Ordering without a global lock.** `MessageExecutor` routes by field hash across threads, so messages for one key stay
ordered while unrelated keys proceed in parallel. Each executor thread draws its work from its own ringos
multi-producer/single-consumer ring buffer, whose task slots are pre-allocated once and filled in place through an
event translator — so handing a message to another thread takes no lock and allocates nothing, and a full queue
applies the idle strategy you chose rather than parking on a monitor.

**Persistence and logging come off the message path entirely.** A FIX engine has to store what it sends and log what it
sees, and both are I/O — the two things you least want between receiving a message and answering it. Staffix's async
store and async logger are decorators you can wrap around any implementation: the session thread hands the operation to
a **Chronicle Queue** and returns, and a background thread does the database write, the file append or the network call.
Chronicle's queue is off-heap and memory-mapped, so handing over costs no allocation and no garbage, and the queued work
survives a process crash rather than living in a buffer that dies with it. A watchdog tracks the health of the backing
store and keeps queueing through an outage instead of pushing the failure back onto the session, and batching is
available where throughput matters more than per-operation latency.

The point is the shape of the guarantee: wrapping a JDBC store in the async decorator means a slow database costs you
queue depth, not round-trip time.

**And none of it counts until the code is compiled.** Every rule above describes compiled code; a JVM started on
Saturday and idle until Monday serves the open interpreted, which is the one moment you cannot afford it. The
[`jvm-warmup`](jvm-warmup) module runs a full loopback FIX session against a synthetic message carrying one field of
every type the codec knows, so C2 has compiled and specialised the whole message path — parser, encoders, session
state machine, and your own store, logger and plugins when you hand it your engine builder — before the first real
order arrives.

---

## Why another FIX engine

Because the FIX engine is not a peripheral. It is the first thing every tick touches on the way in and the last thing
every order touches on the way out — it sits inside your tick-to-trade twice, and there is no path around it.

Which makes one combination self-defeating. You can spend months on the inside of a trading system: lock-free
structures, cache-line padding, allocation-free hot paths, GC pauses hunted down one by one, threads pinned to cores.
Then you put QuickFIX/J at the boundary, and every message in and out of that carefully built machine goes through
something that allocates sixteen kilobytes and takes eighteen microseconds longer per round trip than it needs to. The
engine you chose for the edge is now the slowest component you own, and the garbage it produces lands in the same heap
as everything you tuned. **A low-latency system with a high-latency FIX engine at its edge is not a low-latency
system.**

That is the gap. If you want to speak FIX from Java and you care about latency, open source currently asks you to choose
between two things you should not have to choose between. **QuickFIX/J is the library, and it is not low latency** — it
was designed when a millisecond was a small number, and the garbage is what the design does rather than something
tuning removes. **Artio is low latency, and it is not a library** — it is an Aeron deployment, with a media driver, an
archive and log directories to operate. Both are good at what they are, and both comparisons are made with evidence
[below](#compared-to-quickfixj).

**Staffix is meant to close that gap: a true low-latency FIX engine that is an ordinary Java library.** A dependency, in
your process, on your threads, with no infrastructure to deploy — and the message path built to a rule: **the hot path
is a budget, and every cycle spent there has to justify itself.** Not as an optimisation applied afterwards, but as the
constraint that decides what is allowed into the code at all. A new validation either costs nothing when it is switched
off, or it does not ship.

---

## Compared to QuickFIX/J

QuickFIX/J is the default choice in Java FIX, for good reasons, and this section is not an argument that it is a bad
engine. It is a list of specific, checkable differences.

### Latency

Half the round-trip time on identical settings, and a tighter tail — the numbers are above, from a harness that starts
both engines the same way. Staffix additionally offers a busy-spin mode that QuickFIX/J has no equivalent of.

### Where dictionaries come from

QuickFIX/J's dictionaries are hand-maintained XML files. Staffix generates its dictionaries from the FIX Trading
Community's Orchestra repository, cut at an exact version **and extension pack** — so `FIX50SP2.xml` is 5.0SP2 as
amended through EP98, stated on the root element rather than left to be inferred. Deprecations the standard has declared
are carried through into `@Deprecated` on the generated field classes, setters, groups and enum constants, so your
compiler tells you when you use something the standard has retired. A hand-maintained dictionary carries the deprecated
elements with no indication that they are deprecated.

### FIX protocol conformance

Staffix implements **22** of the official FIX Session conformance test scenarios from
`FIX_Session_Testcases_June_2020` as executable tests that run on every build, alongside interoperability tests that
drive a real QuickFIX/J 3.0.1 engine against Staffix in-process.

### What QuickFIX/J has that Staffix does not

Twenty years of production deployment across the industry, a large community, a mature C++ sibling, far more
integrations, and the accumulated battle-testing that only time provides. Staffix is younger and narrower. If your
constraint is ecosystem maturity rather than latency, that is a real answer and QuickFIX/J is a reasonable one.

---

## Compared to Artio

QuickFIX/J is the popular engine. **Artio** is the serious one, and it is the comparison that matters if latency is why
you are reading this.

Artio came out of Real Logic — the authors of Aeron and Agrona, from the LMAX lineage that produced the Disruptor and,
with it, most of the ideas this whole field is built on. Adaptive Financial Consulting acquired Real Logic in 2022, and
the project is now developed at [`artiofix/artio`](https://github.com/artiofix/artio) under Apache 2.0, with releases on
Maven Central. It is a genuinely high-performance FIX engine, it is in production at demanding institutions, and on the
measurement that separates QuickFIX/J from everything else — garbage — it is Staffix's peer, not its inferior. Anyone
claiming otherwise has not measured it.

Where the two differ is architecture. Artio runs on Aeron: an engine process, a media driver, and an archive, with
sessions journalled through the Aeron log and inter-process traffic over Aeron channels. That design buys durability,
replay and multi-process scale-out as properties of the transport rather than as features bolted on, and it is why Artio
scales to session counts that a single-process engine cannot. It also means an Aeron media driver to deploy, tune and
operate, a log directory to size, and a round trip that crosses process boundaries even when both ends are on one host.

Staffix is a library, in your process, on your threads. There is no media driver, no archive, no second process — a FIX
session is objects on a heap and a socket in an NIO selector you configured. That is a smaller architecture with a
smaller operational surface, and in the round-trip benchmark above it is the faster one: 19.5 µs against 40.4 at stock
settings, 11.2 against 29.7 when both are told to prioritise latency, with a 99th percentile nearly three times tighter
and a worst case twenty-five times tighter in that mode.

**That architecture also shows up long before you measure anything — in what it takes to get a session up.** The fairest
evidence we have is this repository's own benchmark harness: one author, standing up an initiator and an acceptor for
each engine, behind the same interface.

| engine     | lines to stand up an initiator and an acceptor |
|------------|------------------------------------------------|
| QuickFIX/J | 215                                            |
| Staffix    | 318                                            |
| Artio      | 526                                            |

The gap is not verbosity. Artio needs an `ArchivingMediaDriver`, archive contexts and threading modes, **five Aeron
channel URIs per side** — data, control request, control response, replication, recording events — log directories to
place and size, and, in our harness, a priming round trip to stop a lost first message wedging the first measured
iteration. None of it is gratuitous; it is what running on Aeron requires, and every line of it is buying something. But
it is the difference between adding a dependency and adopting a platform, and that is worth knowing at the start rather
than discovering halfway in. Staffix and QuickFIX/J are both, in this respect, just jars.

Three honest qualifications on those numbers:

- **The low-latency configurations are not identical.** Staffix busy-spins; Artio yields. Busy-spinning buys latency by
  burning a core, and the comparison flatters whoever is allowed to spend more CPU.
- **Artio's architecture is in the measurement.** Its round trip includes the Aeron media driver hop that Staffix's does
  not have, because Artio does not have a mode without one. That is a real cost of the design, and it is also a real
  capability of the design that this benchmark gives it no credit for.
- **Tuning someone else's engine is hard.** These are our settings for Artio, not its maintainers'. If you know how to
  configure it better, the harness is in this repository and we would rather publish a corrected number than a
  flattering one — [`ArtioEngine`](benchmarks/src/main/java/org/lolaf/staffix/benchmarks/impl/ArtioEngine.java).

Choose Artio if you need durable replay in the transport, many sessions across processes, or the Aeron stack you are
already running. Choose Staffix if you want a FIX engine that is a dependency rather than an infrastructure component,
and the lowest round-trip latency of the three measured here.

---

## Licence

Apache License 2.0.
