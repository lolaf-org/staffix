# Stores and loggers

A FIX session has to remember what it sent, so it can resend it. It usually also has to record what it saw, for
operations and compliance. Both are I/O, and both sit between receiving a message and answering it, so Staffix makes
them pluggable, and gives you a way to take them off the session thread entirely.

---

## The two things, and why they are different

**A message store is protocol machinery.** Resend requests are answered from it. Lose it and the session cannot
recover a gap. It holds outgoing messages and the session's sequence state.

**A message logger is a record.** Nothing in the protocol depends on it. It exists for you, not for the peer.

They are configured the same way and can be wrapped the same way, but that difference decides how much durability
each one needs.

---

## Message stores

All are registered on the engine and selected per session by `fixMessageStoreInstanceId`. Every implementation is
verified against one shared contract test suite, including one you write yourself.

### In memory

```java
MemoryMessageStoreSettings.builder()
        .

instanceId("acceptor")
        .

maxEntriesInMemory(1024)
        .

build()
```

| setting              | default          |
|----------------------|------------------|
| `maxEntriesInMemory` | `1024`           |
| `useDirectMemory`    | `true`           |
| `messageFilter`      | keeps everything |

Fast and lossy: nothing survives a restart. `maxEntriesInMemory(0)` keeps nothing at all, which is what the
benchmarks use to measure the protocol path alone. Right for development, tests and sessions that reset sequence
numbers on every logon.

### On file

```java
FileMessageStoreSettings.builder()
        .

instanceId("acceptor")
        .

storageDirectoryPath("/var/lib/staffix/acceptor")
        .

build()
```

| setting                     | default                 |
|-----------------------------|-------------------------|
| `storageDirectoryPath`      | *required*              |
| `blocksCount` / `blockSize` | implementation defaults |
| `syncWrites`                | `true`                  |

`syncWrites(true)` is the durable setting and the reason a file store costs more than a memory one: it forces the
write out before the session continues. Turn it off and you trade recovery guarantees for latency; better still, keep
it on and put the store behind the [async wrapper](#taking-io-off-the-session-thread).

### Over JDBC

```java
JdbcMessageStoreSettings.builder()
        .

instanceId("acceptor")
        .

dataSource(myDataSource)
        .

maxMessagesPerSession(1_000_000)
        .

build()
```

| setting                    | default            |
|----------------------------|--------------------|
| `dataSource`               | *required*         |
| `tablePrefix`              | `""`               |
| `maxMessagesPerSession`    | `0` (unlimited)    |
| `pruningCheckInterval`     | 5 minutes          |
| `scheduledExecutorService` | supplied if absent |

Schemas are generated rather than shipped as a fixed `.sql`: the DDL generator plugin produces them for several
dialects via Hibernate. `maxMessagesPerSession` with `pruningCheckInterval` keeps the table bounded.

**A database is on the other side of a network.** Used directly it puts a network round trip inside your FIX round
trip. Wrap it.

### Filtering what is stored

Every store takes a `messageFilter`, a `BiPredicate<MessageType, ByteBuffer>` that decides what *not* to keep. The
default keeps everything. Dropping high-volume message types you will never resend is the cheapest way to make
persistence affordable.

---

## Message loggers

Registered the same way, selected per session by `fixMessageLoggerInstanceId`.

| logger                        | use                                                                                   |
|-------------------------------|---------------------------------------------------------------------------------------|
| `Slf4jMessagesLoggerSettings` | into your existing logging stack. `logIncoming` / `logOutgoing` toggle each direction |
| `FileMessagesLoggerSettings`  | straight to file, without going through a logging framework                           |
| `OtlpMessagesLoggerSettings`  | exports over OTLP, so messages land beside your metrics and traces                    |
| `DemuxMessagesLoggerSettings` | fans out to several of the above                                                      |
| `AsyncMessagesLoggerSettings` | wraps any of them, see below                                                          |

The quickstart uses the SLF4J logger with both directions on, which is why you can watch the session come up.

---

## Taking I/O off the session thread

Both `AsyncMessagesStoreSettings` and `AsyncMessagesLoggerSettings` are **decorators**: they wrap another store or
logger, and change when the work happens rather than what it does. The session thread hands the operation to a
Chronicle Queue and returns; a background thread performs the database write, the file append or the network call.

```java
AsyncMessagesStoreSettings.builder()
        .

wrappedFixMessagesStoreSettings(JdbcMessageStoreSettings.builder()
                .

instanceId("acceptor")
                .

dataSource(myDataSource)
                .

build())
        .

build()
```

Chronicle's queue is off-heap and memory-mapped, so the handover allocates nothing, and queued work survives a
process crash rather than dying with the buffer that held it.

| setting                                      | default    | what it is for                                                                 |
|----------------------------------------------|------------|--------------------------------------------------------------------------------|
| `messageByteBufferSize`                      | `2048`     | sized to your largest message                                                  |
| `useDirectByteBuffer`                        | `true`     | off-heap handover                                                              |
| `underlyingStoreResourceWatchTaskCheckDelay` | 2 seconds  | how often the watchdog re-checks a failed backend                              |
| `flushPendingMessagesOnStartupDelay`         | 60 seconds | how long start waits for writes left by a previous run before failing to start |
| `findWaitForEmptyQueueTimeout`               | 5 seconds  | how long a resend waits for pending writes before failing                      |

**The guarantee to understand:** wrapping a JDBC store in the async decorator means a slow database costs you queue
depth, not round-trip time. A watchdog tracks the backing store's health and keeps queueing through an outage instead
of pushing the failure back onto the session.

**And the trade:** a store answers resend requests, so a message still in the queue is a message not yet in the
database. A resend first waits, up to `findWaitForEmptyQueueTimeout`, for every pending write to land. If they have not
landed by then, or the database is down, the read fails and the session logs out instead of answering: a resend from
an incomplete store would gap fill the missing messages, and the peer would never see them. It asks again on its next
logon. If your
compliance position is that a message must be durably in the database before it goes on the wire, the async wrapper
is not what you want: a queue that survives a crash is not the same guarantee as a committed transaction.

---

## Choosing

| situation                                 | store                          |
|-------------------------------------------|--------------------------------|
| development, tests, benchmarks            | memory                         |
| single process, sequence recovery matters | file, `syncWrites(true)`       |
| shared or queryable persistence           | JDBC behind the async wrapper  |
| any of the above, latency-sensitive       | wrap it in the async decorator |

For logging, start with SLF4J, add the async wrapper the moment logging appears in a latency profile, and use demux
when one destination is not enough.
