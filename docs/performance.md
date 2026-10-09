# Performance

## Measured

A QuoteRequest sent from an initiator to an acceptor over loopback TCP, and the response awaited. Same JMH harness, same
JVM, same fork settings, same machine, all three engines started by the same code:
[`FixEngineRTTBenchmark`](../benchmarks/src/main/java/org/lolaf/staffix/benchmarks/FixEngineRTTBenchmark.java).

**Every engine runs with an in-memory store and message logging switched off.** This measures the protocol path (parse,
dispatch, encode, write) and nothing else. It is not a measurement of any engine's persistence, and no engine here is
doing durable work. Turn a real store on and all three numbers move; what the async wrapper described
[below](#how-the-latency-is-achieved) exists to do is keep that movement out of the round trip.

Round-trip latency, microseconds, **stock settings**: the like-for-like comparison, since this is the only
configuration all three engines support:

| engine           | mean      | p50       | p90       | p99       | p99.9     | max     |
|------------------|-----------|-----------|-----------|-----------|-----------|---------|
| **Staffix**      | **19.48** | **19.14** | **21.76** | **25.02** | **33.22** | **243** |
| QuickFIX/J 3.0.1 | 37.13     | 36.54     | 38.78     | 48.13     | 79.62     | 1858    |
| Artio 0.181      | 40.38     | 29.70     | 47.49     | 203.01    | 315.39    | 7881    |

Staffix's 99th percentile, 25.02 µs, is faster than QuickFIX/J's median. Read the last two columns with more caution
than the rest: a p99.9 and a maximum are the few worst observations of one run, they move between runs, and what they
mostly report is what the JVM did that afternoon rather than what the engine does.

Staffix also runs a busy-spin I/O mode. QuickFIX/J has no equivalent, because the benchmark harness rejects any
non-stock setting for it, so this is not a comparison with QuickFIX/J, only a statement of what the engine reaches when
told to spend CPU on latency:

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

Staffix allocates **less than one byte per round trip**: not one object per message, but a handful of objects across
the whole run, amortised to under a byte. QuickFIX/J allocates sixteen kilobytes for the same work: a new object graph
per message, parsed eagerly into strings, at 399 MB of garbage per second on one session. That is roughly four orders of
magnitude, and it is the difference between a latency profile with no GC in it and one where the collector is a
participant.

Be clear about what this table does not say: **Artio allocates essentially nothing either.** On memory it and Staffix
are peers, and if allocation is what you need to get rid of, either one gets you there.

<sub>JMH 1.37, JDK 21.0.12, 1 fork, 2 warmup + 3 measurement iterations of 10 s, single thread, single session, both
ends on one
host over loopback. Latency figures are sample mode; allocation figures agree across sample, average and throughput
modes. Store and logging configuration per engine: Staffix an in-memory store holding zero entries, QuickFIX/J a
`NoopStoreFactory`, Artio with inbound and outbound message logging disabled. Single-shot mode allocation is excluded:
it measures one cold invocation and is dominated by one-time setup for every engine. Raw results:
[
`jmh-result-FixEngineRTTBenchmark-2026-08-13.json`](../benchmarks/results/jmh-result-FixEngineRTTBenchmark-2026-08-13.json).
This is a benchmark, not a production measurement: one message type, one session, no contention, no network. Run it
yourself:
`mvn clean install -pl benchmarks -am && java -jar benchmarks/target/benchmarks.jar`. For numbers that compare from one
run to the next, use [`benchmarks/run.sh`](../benchmarks/run.sh), which pins each benchmark with `taskset`: set
`BENCHMARK_CPUS` and `BENCHMARK_RTT_CPUS` to the cores of one die on your machine, since its defaults describe ours, and
leave the round trip enough of them for Artio's threads.</sub>

**Every run is kept.** [`benchmarks/results/`](../benchmarks/results) holds the raw JMH JSON for each benchmark and each
date it was run (the round trips above, the id generators, the serdes, the clock), so a number quoted anywhere in this
documentation can be traced to the file it came from, and successive runs can be compared rather than taken on trust.
Drop any of those files into [jmh.morethan.io](https://jmh.morethan.io) to browse and chart it, or several at once to
diff them.

The round trip is the headline, but the serde benchmarks are the ones to read when you are choosing between the forms
a field can take, because that choice is yours to make per field and the cost of each is measured rather than argued:

| benchmark                                                                                               | what it puts side by side                                                                                                                                         |
|---------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| [`IntSerDeBenchmark`](../benchmarks/src/main/java/org/lolaf/staffix/benchmarks/IntSerDeBenchmark.java)     | serializing into a caller's buffer against allocating a fresh array, and against the per-session cache, over integer widths from one digit to `Integer.MIN_VALUE` |
| [`LongSerDeBenchmark`](../benchmarks/src/main/java/org/lolaf/staffix/benchmarks/LongSerDeBenchmark.java)   | decoding 64-bit values, signed against unsigned, up to `Long.MAX_VALUE`                                                                                           |
| [`FloatSerDeBenchmark`](../benchmarks/src/main/java/org/lolaf/staffix/benchmarks/FloatSerDeBenchmark.java) | `DecimalFloat` against `double` and against `BigDecimal`, both directions, the decimal question every FIX price field asks                                       |
| [`FixMessageParserBenchmark`](../benchmarks/src/main/java/org/lolaf/staffix/benchmarks/FixMessageParserBenchmark.java) | the parser alone on a heartbeat, a quote and sixteen quotes arriving in one read, on default validation settings: the parsing loop without the round trip around it |

What they show is the shape of the argument this documentation makes: decoding an int is a few nanoseconds and no garbage,
`DecimalFloat` costs a fraction of `BigDecimal` and allocates nothing where `BigDecimal` allocates on every value, and
writing into a buffer you already own beats handing back a new array. Numbers per value width, per direction and per
JDK are in the result files rather than quoted here, because that is exactly the kind of figure that goes stale.


## How the latency is achieved

Not by one trick. By a set of rules applied everywhere on the message path.

**Nothing is allocated on the happy path.** The parser compares hashes instead of materialising strings, decodes fields
lazily, parsing `SendingTime(52)` only when a check actually needs it, and builds strings, exceptions and collections
only on the reject branch. Encoders come from a pool and are reusable across messages.

**Field values are decoded from bytes, never through a `String`.** This is where a FIX engine spends its parsing time,
so Staffix does not use the JDK's converters, not because they are slow, but because of what they require:
`Integer.parseInt` and `Double.parseDouble` take a `String`, and on a wire protocol that means allocating one before you
can begin. Staffix's serdes read the byte buffer directly, with length-specialised paths for the integer widths a FIX
message actually contains, and decimals decoded into a `DecimalFloat` value rather than a `BigDecimal` object graph. The
`String` that the JDK route makes mandatory never exists.

**Every type on the wire has its own serde, both directions.** Not one generic converter with a type switch, but a
dedicated path for each thing a FIX field can hold: `int` and `long` with length-specialised variants and signed and
unsigned forms, `double`, `boolean`, `char` and raw byte arrays; `DecimalFloat` and `BigDecimal` for prices; `String`
and `UUID`; and the six temporal types the protocol actually uses (UTC timestamp, date and time, the two
timezone-qualified forms, and local market date), which are the fiddliest fields in FIX to parse and the easiest to
parse slowly. Every one is a static method, so there is no serde instance to allocate and no virtual call to dispatch
through. Encoding writes into a buffer you already own, `serialize(value, dst, size, offset)`, rather than handing back
a fresh array, and a timestamp you do not need as an object decodes straight to a primitive `long` of epoch nanoseconds,
so the object is never born at all.

**And where a value must become an object, you choose what it costs.** Each of the object-valued serdes comes in three
forms, picked per field:

- **plain**: a fresh object per message, the ordinary behaviour;
- **cached**: a per-session content-hash map, so a repeated value allocates once and is a lookup thereafter. FIX fields
  are full of values that repeat forever: symbols, CompIDs, account and currency codes;
- **thread-local**: a reused instance whose contents are rewritten in place through `Unsafe`, allocating nothing at
  all. The returned value is valid only within the processing thread's scope, which is the trade you are making. Without
  that flag it degrades to allocating normally and logs a warning once, rather than failing.

`String` and `DecimalFloat` have all three forms; `UUID` has plain and thread-local. That is the pattern the whole
engine follows: the fast path is available, its cost is stated, and nothing silently does the expensive thing.

**The identifiers you mint are on that path too.** Every order needs a `ClOrdID`, and the obvious
`UUID.randomUUID().toString()` costs a contended `SecureRandom`, four allocations and a 36-character string per message:
application code, but on the engine's hot path, and pointless to optimise everything around it. Staffix ships the
generators: `UUIDsGenerator` for time-ordered v7 or random v4 UUIDs, and `SnowflakeId` for 64-bit ids that are a
primitive. A v7 id costs **20 ns and no allocation**, a Snowflake id **19 ns and no allocation**: thread-local forms
written into a reused instance and handed straight to the encoder, so the id reaches the wire without an object being
born. Against `UUID.randomUUID()`'s 111 ns and 128 bytes, measured on the machine and in the run that produced the round
trips above. A v4 id keeps its `SecureRandom` floor, which is the JDK's, not ours. See
[Efficient identifier usage](efficient-identifiers-usage.md).

**Optional behaviour costs nothing when it is off.** The established idiom is a strategy swapped once at construction,
not a branch tested per field. Checksum calculation, garbled-message detection and field processing each have an
`Active…`/`Void…` pair chosen from the session settings; the void implementation does nothing, so a disabled feature is
not a branch on the parsing loop; it is absent from it. Every validation flag is off by default and documents its cost
in its own javadoc.

**The plumbing is lock-free.** Staffix sits on two libraries built for it and shipped with it: `ringos`, a set of
lock-free MPMC/SPSC ring buffers with selectable idle strategies (busy-spin, backoff, yield, wait-notify) and a
hashed-wheel timer, and `betty`, an NIO transport with pluggable select strategies. Publishing to a ring buffer goes
through an event translator so nothing is allocated to enqueue.

**False sharing is designed out.** `@Contended` padding on the concurrent structures, with the build configured for
`-XX:ContendedPaddingWidth=64 -XX:-RestrictContended`.

**You choose where the CPU goes.** The same engine runs on a blocking selector or a busy-spin loop, which is the
difference between the two tables above, and it is one setting.

**Ordering without a global lock.** `MessageExecutor` routes by field hash across threads, so messages for one key stay
ordered while unrelated keys proceed in parallel. Each executor thread draws its work from its own ringos
multi-producer/single-consumer ring buffer, whose task slots are pre-allocated once and filled in place through an event
translator, so handing a message to another thread takes no lock and allocates nothing, and a full queue applies the
idle strategy you chose rather than parking on a monitor.

**Persistence and logging come off the message path entirely.** A FIX engine has to store what it sends and log what it
sees, and both are I/O, the two things you least want between receiving a message and answering it. Staffix's async
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
[`jvm-warmup`](../jvm-warmup) module runs a full loopback FIX session against a synthetic message carrying one field of
every type the codec knows, so C2 has compiled and specialised the whole message path (parser, encoders, session state
machine, and your own store, logger and plugins when you hand it your engine builder) before the first real order
arrives.
