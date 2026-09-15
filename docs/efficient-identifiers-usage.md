# Efficient identifier usage

FIX messages are full of identifiers — `ClOrdID(11)` on every order, `QuoteID(117)` on every quote, `ExecID(17)` on
every execution, and whatever ids your own application attaches to what it processes. They have to be unique across
threads, across restarts and across every instance of your application, and they are minted and decoded on the message
path, at the rate you send and receive. That puts identifier handling in the hot path, right next to the codec.

Staffix ships two answers, both in `org.lolaf.staffix.api.ids`, both able to run without allocating a single object
per message:

| | [UUID](#part-1--uuids) | [Snowflake](#part-2--snowflake-ids) |
|---|---|---|
| size | 128 bits, 36 characters on the wire | 64 bits, up to 19 digits on the wire |
| uniqueness comes from | randomness — nothing to configure | a **node id you must configure** |
| class | `UUIDsGenerator` | `SnowflakeId` |

Part 1 and part 2 cover them in turn; [Choosing between them](#choosing-between-them) is the summary if you only want
the verdict.

---

## Why the obvious answers are expensive

```java
String clOrdId = UUID.randomUUID().toString();   // don't
```

That one line costs, per identifier:

| cost | where it comes from |
|------|---------------------|
| a shared `SecureRandom` | `UUID.randomUUID()` draws from one process-wide instance; concurrent senders contend on it |
| a 16-byte array | allocated inside `randomUUID()` on every call, then discarded |
| a `UUID` | allocated |
| a `String` | `toString()` allocates a 36-character string, plus the `char[]`/byte array behind it |
| a second copy | the encoder then has to write those characters into the output buffer |

Four allocations and a shared contention point for one field. At a few thousand messages a second that is a steady
stream of garbage on the exact path where a GC pause is most expensive.

The usual escapes are worse than they look. A per-session counter is fast but is not unique across restarts unless you
persist it, and not unique across instances at all. A hand-built `symbol + "-" + timestamp + "-" + counter` string
allocates more than the UUID did, not less.

---

# Part 1 — UUIDs

`UUIDsGenerator` is the one entry point:

```java
private static final UUIDsGenerator UUIDS = UUIDsGenerator.instance();
```

| method | returns | allocates | measured |
|--------|---------|-----------|----------|
| `newV7()` | time-ordered version 7 UUID | one `UUID` | 20 ns, 32 B |
| `threadLocalV7()` | time-ordered version 7 UUID | **nothing** | 20 ns, **0 B** |
| `newV4()` | random version 4 UUID | one `UUID`, plus whatever `SecureRandom` allocates | 113 ns, 96 B |
| `threadLocalV4()` | random version 4 UUID | whatever `SecureRandom` allocates | 117 ns, 64 B |
| `threadLocalUuid(msb, lsb)` / `threadLocalUuid(MsbLsb)` | a UUID you already decoded | **nothing** | — |

For comparison, `UUID.randomUUID()` on the same run: **111 ns, 128 B**. Note what the version 7 rows do *not* say:
the thread local form is no slower than the allocating one, so reusing the instance costs nothing in time and saves
the 32 bytes.

**Version 4's cost is its entropy source, not this library.** The JDK's `SecureRandom` implementations allocate inside
every call — 64 B/op for `NativePRNG` filling 16 bytes, 112 B/op for a single `nextLong()`. Drawing both halves in one
`nextBytes` is why `threadLocalV4()` sits at 64 B rather than the ~224 B two `nextLong()` calls would cost, but the
floor is the platform's, not ours. Swap the source for a non-cryptographic one and it disappears entirely:

| `threadLocalV4()` with | ns/op | B/op |
|---|---|---|
| the default per-thread `SecureRandom` | 117 | 64 |
| `dst -> ThreadLocalRandom.current().nextBytes(dst)` | **16** | **0** |

That is the whole of it: ~100 ns and every allocated byte belong to `SecureRandom`. **Version 7 has no such floor** —
its `rand_b` bits come from a plain `ThreadLocalRandom` — which is the practical reason to prefer it on the message
path, on top of everything in [Version 7, and why it matters downstream](#version-7-and-why-it-matters-downstream).
Only reach for a non-crypto v4 source knowing what you give up: ids an attacker can predict from earlier ones. If a
`ClOrdID` is guessable, the question is whether anything in your system treats it as a secret.

<sub>JMH 1.37, average time, 1 fork, 2 warmup + 3 measurement iterations of 10 s, single thread, JDK 21.0.12,
`-prof gc` - the same machine, JVM and run settings as the round trips in the
[README](../README.md#measured), so the two sets of numbers can be read against each other. Raw results:
[`jmh-result-IdGenerationBenchmark-2026-08-13.json`](../benchmarks/results/jmh-result-IdGenerationBenchmark-2026-08-13.json).
The version 4 rows run over both random sources, and every UUID row over both instance modes. Reproduce with
`java -jar benchmarks/target/benchmarks.jar IdGenerationBenchmark -prof gc`
([`IdGenerationBenchmark`](../benchmarks/src/main/java/org/lolaf/staffix/benchmarks/IdGenerationBenchmark.java)).
Add `-t 8` for the contended picture, which is where the per-thread source pays off against the JDK's shared one.</sub>

Version 7 values come from a `UUIDv7` sequence: a lock-free CAS on a single `long` packing the millisecond timestamp
and a 12-bit counter, so every value from one sequence is strictly greater than the one before it, across threads.
Version 4 values are the classic random ones, with the same bit layout and the same cryptographic strength as
`UUID.randomUUID()` — but drawn from a **per-thread** `SecureRandom`, so senders never contend on a shared one, and
read as a single 16-byte draw into a reused buffer rather than two `nextLong()` calls (which would go through
`SecureRandom.next(int)`, allocating a `byte[]` each time).

The random source is a constructor argument, and its type — `RandomBytesSource`, one method, `nextBytes(byte[])` — is
the signature every JDK random API already has, so any of them plugs in as a method reference and none needs an
adapter:

```java
new UUIDsGenerator(uuidV7, secureRandom::nextBytes);                              // the default, made explicit
new UUIDsGenerator(uuidV7, RandomGenerator.of("Xoshiro256PlusPlus")::nextBytes);  // JDK 17+, fast and not crypto
new UUIDsGenerator(uuidV7, new Random(42)::nextBytes);                            // reproducible, for tests
```

`java.util.random.RandomGenerator` itself is JDK 17 and this library targets Java 11, which is why the parameter is
not that type — but a method reference to it works on any runtime that has it. Two things become yours when you pass
your own source: **thread safety** (most `RandomGenerator` implementations have none, unlike `SecureRandom` and
`Random`), and **strength** — a fast non-crypto generator gives you valid, unique, *guessable* ids.

`UUIDsGenerator.instance(id)` gives a generator dedicated to a subsystem. It is backed by `UUIDv7.instance(id)` — the
same id — so a component reaching for the raw sequence and one reaching for the generator draw from one ordered
stream, while a *different* id shares nothing with it.

## Zero allocation on the encoding path

`threadLocalV7()` hands back a `UUID` instance that belongs to the calling thread and is overwritten in place on the
next call. The encoder writes it straight out as 36 ASCII characters — no `String`, no intermediate array:

```java
newOrderSingleEncoder.begin()
        .setTransactTime(clock.now(), TimeUnit.MICROSECONDS)
        .setSymbol("XAG/USD")
        .setSide(Side.SideValues.BUY)
        .addUUID(ClOrdID.get(), UUIDS.threadLocalV7())   // no allocation, at all
        .setOrderQty(1234);
fixSession.send(newOrderSingleEncoder, null);
```

`addUUID` goes through `UUIDSerde.format(UUID, ByteBuffer)`, which writes the hex digits and the four hyphens directly
into the encoder's output buffer. The full working example is
[`TradingExample`](../examples/staffix-api-examples/src/main/java/org/lolaf/staffix/examples/TradingExample.java).

## Zero allocation on the decoding path

The mirror image, on the way in. A UUID-typed field mapped with `ObjectInstanceStrategy.THREAD_LOCAL` is decoded by
`UUIDThreadLocalSerde`, which parses the 36 characters into a reusable `MsbLsb` holder and then writes those two
halves into the thread's reused `UUID` instance:

```java
decoderMapper.mapUUIDField(ClOrdID.get(), order::setClOrdId, null, ObjectInstanceStrategy.THREAD_LOCAL);
```

| strategy | behaviour |
|----------|-----------|
| `NEW_INSTANCE` (default) | a fresh `UUID` per decoded value; safe to keep and to hand to another thread |
| `THREAD_LOCAL` | zero allocation, valid until the next decode on that thread |
| `CACHED` | rejected with an `IllegalStateException` — a cache of unique identifiers would only grow |

## The rules for the thread-local values

There is exactly one instance per thread per flavour, mutated in place. So:

- **Never hand one to another thread.** The two 64-bit halves are written as two separate stores with no ordering
  between them, so a concurrent reader can observe a *torn* value — the top half of one UUID with the bottom half of
  the next. This is not a stale read you can shrug at.
- **Never store one** in a field, a collection, or a queue that outlives the call.
- When a value has to survive either of those, use `newV7()` / `newV4()`, or copy it with
  `new UUID(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits())`.

Writing into a `UUID`'s final fields needs the `UnsafeOperations` API, so the zero-allocation methods require the
JVM to be started with at least:

```
--add-opens java.base/jdk.internal.misc=ALL-UNNAMED
```

(on Java 11–14 the implementation also needs `jdk.internal.ref` and `sun.nio.ch` opened — see
[Tuning for latency](tuning-for-latency.md) for the full flag set). Without them the `threadLocal*` methods fail fast
with an `IllegalStateException` whose cause names the exact missing flag, rather than silently allocating behind your
back. `newV7()` / `newV4()` work on any runtime, flags or not.

## Version 7, and why it matters downstream

A v4 UUID is 122 random bits. A v7 UUID is a 48-bit Unix millisecond timestamp, a 12-bit counter and 62 random bits
([RFC 9562 §5.7](https://www.rfc-editor.org/rfc/rfc9562#name-uuid-version-7)). Two consequences:

**Your ids sort themselves.** Byte order, string order and creation order are the same order. Logs, message stores and
blotters sort chronologically with no extra column, and you can read the creation time back out of an id you find in a
log line.

**Your database indexes stop fragmenting.** This is the big one. Random ids insert into random B-tree pages: every
insert dirties a different page, splits pages that were already full, and evicts cache that the next insert wanted.
Time-ordered ids append at the right-hand edge of the index — the same access pattern as a sequence, with none of the
coordination. PostgreSQL 18 made this a first-class thing by adding a built-in `uuidv7()` function
([release notes](https://www.postgresql.org/docs/current/release-18.html)); Aiven's
[write-up](https://aiven.io/blog/exploring-postgresql-18-new-uuidv7-support) measures ~27% faster inserts and
substantially faster time-ordered queries against v4 on the same schema.

That argument applies to *your* tables — whatever your application persists keyed by the identifiers minted here.
Staffix's own JDBC message store keys on `(session_id, sequence_number)` and is not affected either way.

## When to use v4 instead

A v7 UUID **publishes its creation time**, to the millisecond, to anyone who sees it — including your counterparty.
That is usually a feature and occasionally a leak. When ids must not carry timing information, or must not be
adjacent to each other in any ordering, use `newV4()` / `threadLocalV4()` — knowing that you give up the index
locality, and that cryptographic entropy costs both time and garbage: roughly 6× the latency of a v7 id, and 64 B per
id that belongs to the JDK's `SecureRandom`. If you need the *shape* of a v4 (no timestamp, no ordering) but not its
unpredictability, passing a non-crypto `RandomBytesSource` brings both back to v7's level, at the cost described
above.

## The one limit worth knowing

A single v7 sequence mints at most **4096 ids per millisecond** — the width of the counter — which is about 4 million
per second. Generation never blocks: past that rate the counter overflow borrows into the timestamp, so ordering and
uniqueness still hold, but the embedded timestamp starts running ahead of the wall clock and stays ahead for as long
as the burst lasts. If you genuinely need more than that with truthful timestamps, shard across ids:

```java
UUIDsGenerator ordersA = UUIDsGenerator.instance("orders-a");
UUIDsGenerator ordersB = UUIDsGenerator.instance("orders-b");
```

Each id owns an independent sequence and therefore its own 4096/ms budget.

---

# Part 2 — Snowflake ids

Where a UUID carries 128 bits and 36 characters, a Snowflake id is a single `long`. Nothing to allocate — not even a
thread-local instance to be careful with — nothing to hash, and a primary key a quarter the size of a stored UUID.

```java
SnowflakeId ids = new SnowflakeId(42);   // 42 is this process's node id
long id = ids.nextId();
```

## The layout

```
 63   62                                    22        12                    0
 +---+-------------------------------------+----------+---------------------+
 | 0 | timestamp (41 bits, ms since epoch) | node (10)| sequence (12 bits)  |
 +---+-------------------------------------+----------+---------------------+
```

Twitter's original split, with the epoch moved to `DEFAULT_EPOCH_MILLIS` (2024-01-01, so 41 bits last until ~2093) and
the middle field treated as one flat node id rather than the traditional 5 datacenter + 5 worker bits. The sign bit is
always `0`, so ids are positive and their natural `long` ordering is their creation ordering. Other implementations
split the same 64 bits differently — Discord uses 42/5+5/12, Instagram 41/13/10, Sonyflake 39 bits of 10 ms units with
16 machine bits and 8 sequence bits — so **ids are not comparable across layouts**; decode them with the generator
that minted them:

```java
SnowflakeId.nodeIdOf(id);        // 42
SnowflakeId.sequenceOf(id);      // 0..4095
ids.timestampMillisOf(id);       // Unix millis, using this generator's epoch
```

## The node id is the whole contract

Uniqueness here comes from that number, not from randomness. **Two generators sharing a node id will mint identical
ids.** So:

- give every process a distinct node id — from configuration, an orchestrator-assigned ordinal, a `StatefulSet`
  index, whatever you already have that is unique and stable;
- do not create several `SnowflakeId` instances on one node id inside a process (that is also why there is no
  `instance("name")` registry, unlike `UUIDv7`);
- `SnowflakeId.instance()` reads the node id from the `org.lolaf.staffix.api.ids.SnowflakeId.nodeId` system property.
  With the property unset it falls back to node id `0` and logs an `INFO` line saying so — fine for a single process,
  and a duplicate-id generator the day you run two. Set the property in any deployment with more than one process. A
  property that *is* set but unusable fails immediately, on the first `instance()` call, rather than guessing.

## On the wire, and back

A Snowflake id is a primitive in both directions, so there is no instance strategy to think about and nothing to
invalidate:

```java
encoder.addLong(OrderID.get(), ids.nextId());                    // writes the digits, allocates nothing
decoderMapper.mapLongField(OrderID.get(), order::setOrderId, 0); // decodes into a long field, allocates nothing
```

## Clocks and bursts

A node mints at most **4096 ids per millisecond** (~4M/s), the same ceiling as a v7 sequence and for the same reason.
Past it, the sequence overflow borrows into the timestamp: ordering and uniqueness hold, the embedded timestamp runs
ahead of the wall clock until the burst ends.

Classic implementations throw, or block, when the clock steps backwards. This one does neither — the counter simply
advances, so an NTP correction or a leap-second smear costs a little timestamp accuracy and never costs ordering,
uniqueness or availability. An id is always strictly greater than the one before it, from the same generator, across
threads.

---

# Choosing between them

| | UUID v7 | UUID v4 | Snowflake |
|---|---|---|---|
| size in memory | 128 bits (plus object header, unless thread local) | 128 bits | **64 bits, a primitive** |
| size on the wire | 36 chars | 36 chars | **≤ 19 digits** |
| coordination needed | none | none | **a unique node id per process** |
| collision risk | negligible | negligible | **certain, if a node id is reused** |
| time-ordered | yes | no | yes |
| reveals creation time | yes | no | yes |
| zero-allocation path | `threadLocalV7()`, with the caveats above | only with a non-crypto source | inherent — it is a `long` |
| cost per id | 20 ns, 0 B | 117 ns, 64 B (16 ns, 0 B non-crypto) | **19 ns, 0 B** |
| needs `--add-opens` for that | yes | yes | **no** |
| index locality in a database | good | poor | good |
| ceiling | 4096/ms per sequence | none | 4096/ms per node |

**Use UUID v7** when you want time-ordered ids with no operational contract to uphold — nothing to assign, nothing to
keep unique across deployments. This is the sane default, and the one to reach for when ids cross organisational
boundaries.

**Use UUID v4** when the id must not carry a timestamp.

**Use Snowflake** when the id is yours end to end and its cost shows up in bulk: a high-volume table where 8 bytes
versus 16 per row (times every index) matters, a hot in-memory map keyed by id, or a field you would rather compare as
a primitive than as an object. The price is that node ids become part of your deployment configuration, and getting
that wrong is silent — duplicate ids, not an exception.

If you cannot answer "which process holds node id 3?" for every process you run, use UUIDs.

---

## Cheat sheet

```java
// the generators, held once, not per message
private static final UUIDsGenerator UUIDS = UUIDsGenerator.instance();
private static final SnowflakeId SNOWFLAKES = SnowflakeId.instance();

// hot path, value used and forgotten within the call
newOrderSingleEncoder.addUUID(ClOrdID.get(), UUIDS.threadLocalV7());

// value kept in a map, put on a queue, or read by another thread
UUID clOrdId = UUIDS.newV7();

// no timing information in the id
UUID opaque = UUIDS.newV4();

// decoding a UUID without allocating
decoderMapper.mapUUIDField(ClOrdID.get(), order::setClOrdId, null, ObjectInstanceStrategy.THREAD_LOCAL);

// 64-bit id, no allocation either way, node id configured per process
long orderId = SNOWFLAKES.nextId();
executionReportEncoder.addLong(OrderID.get(), orderId);
decoderMapper.mapLongField(OrderID.get(), order::setOrderId, 0);
```

Sources: [PostgreSQL 18 release notes](https://www.postgresql.org/docs/current/release-18.html) ·
[Exploring PostgreSQL 18's new UUIDv7 support (Aiven)](https://aiven.io/blog/exploring-postgresql-18-new-uuidv7-support) ·
[RFC 9562](https://www.rfc-editor.org/rfc/rfc9562) · [How Snowflake IDs work](https://singhajit.com/snowflake-id-guide/)
