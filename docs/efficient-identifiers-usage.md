# Efficient identifier usage

FIX messages are full of identifiers: `ClOrdID(11)`, `QuoteID(117)`, `ExecID(17)`, plus your own. They must be unique
across threads, restarts and instances, and they are minted and decoded on the message path, at the rate you send and
receive.

Staffix ships two generators in `org.lolaf.staffix.api.ids`, both able to run without allocating per message:

| | [UUID](#part-1-uuids) | [Snowflake](#part-2-snowflake-ids) |
|---|---|---|
| size | 128 bits, 36 characters on the wire | 64 bits, up to 19 digits on the wire |
| uniqueness comes from | randomness, nothing to configure | a **node id you must configure** |
| class | `UUIDsGenerator` | `SnowflakeId` |

In a hurry: [Choosing between them](#choosing-between-them) and the [cheat sheet](#cheat-sheet).

---

## Why not `UUID.randomUUID().toString()`

Per identifier it draws from one process-wide `SecureRandom` that concurrent senders contend on, then allocates a
16-byte array, a `UUID` and a 36-character `String`, which the encoder copies again into its buffer. That is four
allocations for one field, on the path where a GC pause costs the most.

The usual escapes are worse: a per-session counter is not unique across restarts or instances, and a hand-built
`symbol + "-" + timestamp + "-" + counter` allocates more than the UUID did.

---

# Part 1: UUIDs

```java
private static final UUIDsGenerator UUIDS = UUIDsGenerator.instance();
```

| method | returns | measured |
|--------|---------|----------|
| `newV7()` | a new time-ordered version 7 UUID | 20 ns, 32 B |
| `threadLocalV7()` | the same, in a reused per-thread instance | 20 ns, **0 B** |
| `newV4()` | a new random version 4 UUID | 113 ns, 96 B |
| `threadLocalV4()` | the same, in a reused per-thread instance | 117 ns, 64 B |
| `threadLocalUuid(msb, lsb)` | a UUID you already decoded, in the reused instance | 0 B |

`UUID.randomUUID()` on the same run: 111 ns, 128 B. Reusing the instance costs no time.

**Version 4's cost is `SecureRandom`, not this library**: the JDK's implementations allocate inside every call. Each
thread has its own, so senders never contend, but that cost remains. Version 7 draws its random bits from
`ThreadLocalRandom` and has no such floor, which is the practical reason to prefer it on the message path.

The random source is a constructor argument taking any `nextBytes(byte[])` method reference:

```java
new UUIDsGenerator(uuidV7, secureRandom::nextBytes);                              // the default
new UUIDsGenerator(uuidV7, RandomGenerator.of("Xoshiro256PlusPlus")::nextBytes);  // JDK 17+, fast, not crypto
new UUIDsGenerator(uuidV7, new Random(42)::nextBytes);                            // reproducible, for tests
```

A non-crypto source brings `threadLocalV4()` down to 16 ns and 0 B, but the ids become **guessable**, and **thread
safety** is then yours: most `RandomGenerator` implementations have none.

`UUIDsGenerator.instance(id)` gives a generator with its own v7 sequence, shared with `UUIDv7.instance(id)` on the same
id.

<sub>JMH, single thread, JDK 21, `-prof gc`, same machine as the [README](../README.md#measured) round trips. Raw
results: [`jmh-result-IdGenerationBenchmark-2026-08-13.json`](../benchmarks/results/jmh-result-IdGenerationBenchmark-2026-08-13.json).
Reproduce with `java -jar benchmarks/target/benchmarks.jar IdGenerationBenchmark -prof gc`, and add `-t 8` for the
contended picture.</sub>

## Encoding and decoding without allocating

`threadLocalV7()` returns a `UUID` owned by the calling thread and overwritten on its next call. `addUUID` writes it
straight into the encoder's buffer as 36 ASCII characters:

```java
newOrderSingleEncoder.begin()
        .setSymbol("XAG/USD")
        .addUUID(ClOrdID.get(), UUIDS.threadLocalV7())   // no allocation
        .setOrderQty(1234);
```

On the way in, map the field with `THREAD_LOCAL`:

```java
decoderMapper.mapUUIDField(ClOrdID.get(), order::setClOrdId, null, ObjectInstanceStrategy.THREAD_LOCAL);
```

| strategy | behaviour |
|----------|-----------|
| `NEW_INSTANCE` (default) | a fresh `UUID` per value; safe to keep and to pass to another thread |
| `THREAD_LOCAL` | zero allocation, valid until the next decode on that thread |
| `CACHED` | rejected: a cache of unique ids would only grow |

[`TradingExample`](../examples/staffix-api-examples/src/main/java/org/lolaf/staffix/examples/TradingExample.java) does
both.

## The rules for thread-local values

There is one instance per thread, mutated in place:

- **Never hand one to another thread.** Its two halves are written by separate stores, so a reader on another thread
  can see the top half of one UUID with the bottom half of the next.
- **Never store one** beyond the call: not in a field, a collection or a queue.
- When a value must survive, use `newV7()` / `newV4()`, or copy it with
  `new UUID(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits())`.

Overwriting a `UUID` in place needs, at least:

```
--add-opens java.base/jdk.internal.misc=ALL-UNNAMED
```

(Java 11–14 also need `jdk.internal.ref` and `sun.nio.ch`; see [Tuning for latency](tuning-for-latency.md).) Without
it, the `threadLocal*` methods throw an `IllegalStateException` naming the missing flag rather than silently
allocating. `newV7()` / `newV4()` work on any runtime.

## Why version 7

A v7 UUID is a 48-bit millisecond timestamp, a 12-bit counter and 62 random bits
([RFC 9562](https://www.rfc-editor.org/rfc/rfc9562#name-uuid-version-7)); a v4 is 122 random bits.

**Ids sort by creation time.** Byte order, string order and creation order agree, so logs and blotters sort without an
extra column, and an id found in a log tells you when it was minted. Within one sequence, every id is strictly greater
than the previous one, across threads.

**Database indexes stop fragmenting.** Random ids insert into random B-tree pages; time-ordered ids append at the
right-hand edge, like a sequence. PostgreSQL 18 added a built-in `uuidv7()` for this reason, and
[Aiven measured](https://aiven.io/blog/exploring-postgresql-18-new-uuidv7-support) ~27% faster inserts than v4. This is
about your own tables: Staffix's JDBC message store keys on `(session_id, sequence_number)`.

**Use v4 instead** when an id must not reveal its creation time, since a v7 publishes it to the millisecond to anyone
who sees it, your counterparty included. You give up index locality and pay ~6× the latency and 64 B per id.

## The limit

A v7 sequence mints at most **4096 ids per millisecond** (~4M/s). It never blocks: past that rate the counter borrows
into the timestamp, so ordering and uniqueness hold but the timestamp runs ahead of the clock while the burst lasts.
For more with truthful timestamps, shard: each `UUIDsGenerator.instance("orders-a")`, `instance("orders-b")`, … has
its own budget.

---

# Part 2: Snowflake ids

A Snowflake id is a single `long`: nothing to allocate, no thread-local instance to be careful with, and a primary key
half the size of a UUID.

```java
SnowflakeId ids = new SnowflakeId(42);   // 42 is this process's node id
long id = ids.nextId();
```

```
 63   62                                    22        12                    0
 +---+-------------------------------------+----------+---------------------+
 | 0 | timestamp (41 bits, ms since epoch) | node (10)| sequence (12 bits)  |
 +---+-------------------------------------+----------+---------------------+
```

The epoch is `DEFAULT_EPOCH_MILLIS` (2024-01-01, good until ~2093). The sign bit is always `0`, so ids are positive and
sort by creation time as plain `long`s. Other Snowflake implementations split the 64 bits differently, so decode an id
with the generator that minted it:

```java
SnowflakeId.nodeIdOf(id);        // 42
SnowflakeId.sequenceOf(id);      // 0..4095
ids.timestampMillisOf(id);       // Unix millis
```

## The node id is the whole contract

**Two generators with the same node id mint identical ids**, and nothing warns you. So:

- give every process a distinct, stable node id: from configuration, an orchestrator ordinal, a `StatefulSet` index;
- create one `SnowflakeId` per node id in a process;
- `SnowflakeId.instance()` reads it from the `org.lolaf.staffix.api.ids.SnowflakeId.nodeId` system property. Unset, it
  falls back to `0` and logs it at `INFO`: fine for one process, duplicate ids the day you run two. A value that is set
  but invalid fails on the first call.

## On the wire, clocks and bursts

It is a primitive both ways:

```java
encoder.addLong(OrderID.get(), ids.nextId());                    // no allocation
decoderMapper.mapLongField(OrderID.get(), order::setOrderId, 0); // no allocation
```

The limit is the same as v7: **4096 ids per millisecond per node**, with the same borrowing past it. When the clock
steps backwards (an NTP correction, a leap-second smear) the generator neither throws nor blocks as classic
implementations do: the counter keeps advancing, so ids stay unique and strictly increasing, at the cost of a little
timestamp accuracy.

---

# Choosing between them

| | UUID v7 | UUID v4 | Snowflake |
|---|---|---|---|
| size on the wire | 36 chars | 36 chars | **≤ 19 digits** |
| coordination needed | none | none | **a unique node id per process** |
| collision risk | negligible | negligible | **certain, if a node id is reused** |
| time-ordered, reveals creation time | yes | no | yes |
| cost per id | 20 ns, 0 B (thread local) | 117 ns, 64 B | **19 ns, 0 B** |
| needs `--add-opens` for zero allocation | yes | yes | **no** |
| database index locality | good | poor | good |
| ceiling | 4096/ms per sequence | none | 4096/ms per node |

**UUID v7** is the default: time-ordered, nothing to assign or keep unique across deployments, safe to hand to other
organisations.

**UUID v4** when the id must not carry a timestamp.

**Snowflake** when the id is yours end to end and its size matters in bulk: a high-volume table, a hot map keyed by id,
a field you would rather compare as a primitive. Node ids then become part of your deployment configuration. If you
cannot say which process holds node id 3, use UUIDs.

---

## Cheat sheet

```java
// held once, not per message
private static final UUIDsGenerator UUIDS = UUIDsGenerator.instance();
private static final SnowflakeId SNOWFLAKES = SnowflakeId.instance();

// hot path, value used and forgotten within the call
newOrderSingleEncoder.addUUID(ClOrdID.get(), UUIDS.threadLocalV7());

// value kept, queued, or read by another thread
UUID clOrdId = UUIDS.newV7();

// no timing information in the id
UUID opaque = UUIDS.newV4();

// decoding a UUID without allocating
decoderMapper.mapUUIDField(ClOrdID.get(), order::setClOrdId, null, ObjectInstanceStrategy.THREAD_LOCAL);

// 64-bit id, node id configured per process
long orderId = SNOWFLAKES.nextId();
executionReportEncoder.addLong(OrderID.get(), orderId);
decoderMapper.mapLongField(OrderID.get(), order::setOrderId, 0);
```
