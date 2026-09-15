# Staffix Async Messages Store

A high-performance asynchronous wrapper for FIX message stores that decouples message storage operations from the FIX
protocol processing thread using a persistent queue backed by Chronicle Queue.

## Overview

The Async Messages Store is a **decorator/wrapper** that adds asynchronous, non-blocking behavior to any Staffix message
store implementation (JDBC, file-based, etc.). It eliminates I/O latency from the critical FIX protocol path by:

1. **Queuing store operations** in a persistent Chronicle Queue
2. **Processing store operations asynchronously** in dedicated background thread
3. **Supporting batching** for high-throughput scenarios
4. **Monitoring underlying storage health** and handling temporary unavailability of underlying FIX messages storage
   resources

This is especially valuable when wrapping stores with slow or unreliable backends (databases, network storage, etc.).

## Key Features

- **Zero-Copy Design**: Uses Chronicle Queue's off-heap memory for minimal GC impact
- **Persistence**: Messages survive process crashes via Chronicle Queue's memory-mapped files
- **Batching Support**: Optional batch processing for maximum throughput
- **Fault Tolerance**: Automatic watchdog monitors underlying storage health and queues operations during outages
- **Configurable Throughput**: Adjustable thread count, batch sizes, and queue settings
- **Queue Monitoring**: Built-in thresholds (normal/warn/critical) with notification callbacks

## Architecture

```
FIX Engine Thread                Background Thread(s)
     |                                   |
     v                                   v
+----------+                      +------------+
| storeXXX |  --(queue)-->        | Chronicle  |
| methods  |                      |   Queue    |
+----------+                      +------------+
                                        |
                                        v
                                 +--------------+
                                 | Wrapped Store|
                                 | (JDBC, File, |
                                 |  Memory...)  |
                                 +--------------+
```

## Maven Dependency

```xml

<dependency>
    <groupId>org.lolaf.staffix</groupId>
    <artifactId>staffix-messages-store-async-impl</artifactId>
    <version>${staffix.version}</version>
</dependency>
```

## Usage

### Basic Configuration

Wrap any existing message store with async behavior:

```java

import org.lolaf.staffix.stores.messages.async.AsyncMessagesStoreSettings;
import org.lolaf.staffix.stores.core.async.AsyncStoreSettings;

// Your existing store settings (e.g., JDBC)
JdbcMessageStoreSettings jdbcSettings = JdbcMessageStoreSettings.builder()
        .dataSource(dataSource)
        .build();

        // Wrap with async behavior
        AsyncMessagesStoreSettings asyncSettings = AsyncMessagesStoreSettings.builder()
                .asyncStoreSettings(AsyncStoreSettings.builder()
                        .asyncQueueDirectory("./chronicle-queue")
                        .readerThreadsCount(2)
                        .build())
                .wrappedFixMessagesStoreSettings(jdbcSettings)
                .build();
```

### High-Throughput Configuration with Batching

For maximum throughput when the underlying store supports batching:

```java
AsyncMessagesStoreSettings asyncSettings = AsyncMessagesStoreSettings.builder()
        .asyncStoreSettings(AsyncStoreSettings.builder()
                .asyncQueueDirectory("./chronicle-queue")
                .readerThreadsCount(4)                           // Multiple reader threads
                .eventsBatching(100)                             // Process 100 events per batch
                .batchingFlushInterval(Duration.ofMillis(50))   // Max 50ms latency
                .readerThreadMaxEventsProcessingPerStore(2048)  // Process up to 2048 events per loop
                .build())
        .wrappedFixMessagesStoreSettings(jdbcSettings)
        .logByteBufferSize(4096)                            // Larger buffer for big messages
        .useDirectByteBuffer(true)                          // Use direct buffers for zero-copy
        .build();
```

### Queue Monitoring

Monitor queue depth with thresholds and callbacks:

```java
AsyncMessagesStoreSettings asyncSettings = AsyncMessagesStoreSettings.builder()
        .asyncStoreSettings(AsyncStoreSettings.builder()
                .asyncQueueDirectory("./chronicle-queue")
                .queueSizeThresholdsSettings(AsyncStoreSettings.QueueSizeThresholdsSettings.builder()
                        .normal(10)                                  // < 10 messages is normal
                        .warn(100)                                   // 10-100 is warning
                        .critical(1000)                              // > 100 is critical
                        .evaluationInterval(Duration.ofSeconds(5))   // Check every 5 seconds
                        .build())
                .queueSizeThresholdsListener(new QueueSizeThresholdsListener() {
                    @Override
                    public void onThresholdReached(FixSessionId sessionId, ThresholdLevel level, long queueSize) {
                        log.warn("Session {} queue at {} level with {} pending messages",
                                sessionId, level, queueSize);
                        // Trigger alerts, metrics, throttling, etc.
                    }
                })
                .build())
        .wrappedFixMessagesStoreSettings(jdbcSettings)
        .build();
```

### Custom Chronicle Queue Configuration

Fine-tune Chronicle Queue behavior per session:

```java
AsyncMessagesStoreSettings asyncSettings = AsyncMessagesStoreSettings.builder()
        .asyncStoreSettings(AsyncStoreSettings.builder()
                .asyncQueueDirectory("./chronicle-queue")
                .rollCycleProvider(sessionId -> {
                    // High-volume sessions: roll hourly
                    // Low-volume sessions: roll daily
                    return sessionId.getId().startsWith("HIGHVOL")
                            ? RollCycles.FAST_HOURLY
                            : RollCycles.FAST_DAILY;
                })
                .builderConfigurer((sessionId, builder) -> {
                    // Custom tuning per session
                    builder.blockSize(256 * 1024 * 1024);  // 256MB blocks
                    builder.indexSpacing(256);              // Index every 256th entry
                })
                .build())
        .wrappedFixMessagesStoreSettings(jdbcSettings)
        .build();
```

### Handling Underlying Storage Failures

The async store automatically handles temporary storage failures:

```java
AsyncMessagesStoreSettings asyncSettings = AsyncMessagesStoreSettings.builder()
        .asyncStoreSettings(AsyncStoreSettings.builder()
                .asyncQueueDirectory("./chronicle-queue")
                .build())
        .wrappedFixMessagesStoreSettings(jdbcSettings)
        .underlyingStoreResourceWatchTaskCheckDelay(Duration.ofSeconds(5))  // Check every 5s
        .flushPendingMessagesOnStartupDelay(Duration.ofMinutes(2))          // Flush backlog on startup
        .build();
```

**Behavior:**

1. When `wrappedStore.isUnderlyingStorageResourceAvailable()` returns `false`, the async store:
    - Unregisters from background thread processing
    - Continues queuing incoming store operations
    - Starts a watchdog thread checking availability every 5 seconds

2. When the underlying store becomes available:
    - The watchdog reregisters with background threads
    - Queued operations are processed
    - Normal operation resumes

3. On startup, if messages are queued from a previous crash:
    - Waits up to 2 minutes to flush them
    - Logs progress and remaining count

## Configuration Reference

### AsyncMessagesStoreSettings

| Parameter                                    | Type                     | Default    | Description                                         |
|----------------------------------------------|--------------------------|------------|-----------------------------------------------------|
| `asyncStoreSettings`                         | AsyncStoreSettings       | *Required* | Core async store configuration                      |
| `wrappedFixMessagesStoreSettings`            | FixMessagesStoreSettings | *Required* | Settings for the wrapped store (JDBC, file, etc.)   |
| `underlyingStoreResourceWatchTaskCheckDelay` | Duration                 | 2 seconds  | How often to check if failed storage is back online |
| `flushPendingMessagesOnStartupDelay`         | Duration                 | 60 seconds | Max time to flush pending messages on startup       |
| `logByteBufferSize`                          | int                      | 2048       | Buffer size for message data                        |
| `useDirectByteBuffer`                        | boolean                  | true       | Use direct (off-heap) buffers for zero-copy I/O     |

### AsyncStoreSettings

| Parameter                                 | Type                              | Default               | Description                                   |
|-------------------------------------------|-----------------------------------|-----------------------|-----------------------------------------------|
| `asyncQueueDirectory`                     | String                            | *Required*            | Directory for Chronicle Queue files           |
| `readerThreadsCount`                      | int                               | 1                     | Number of background threads processing queue |
| `eventsBatching`                          | int                               | 0 (disabled)          | Number of events to batch (0 = no batching)   |
| `batchingFlushInterval`                   | Duration                          | 100ms                 | Max latency before flushing incomplete batch  |
| `readerThreadMaxEventsProcessingPerStore` | int                               | 1024                  | Max events per store per reader loop          |
| `readerThreadsMaxSleepInterval`           | Duration                          | 10ms                  | Max sleep time when queue is empty            |
| `rollCycleProvider`                       | Function<FixSessionId, RollCycle> | FAST_HOURLY           | Chronicle Queue roll cycle per session        |
| `builderConfigurer`                       | BiConsumer                        | (no-op)               | Customize Chronicle Queue builder per session |
| `threadFactory`                           | ThreadFactory                     | FastThreadLocalThread | Factory for reader threads                    |
| `queueSizeThresholdsSettings`             | QueueSizeThresholdsSettings       | See below             | Queue depth monitoring thresholds             |
| `queueSizeThresholdsListener`             | QueueSizeThresholdsListener       | (no-op)               | Callback for threshold violations             |

### QueueSizeThresholdsSettings

| Parameter            | Type     | Default    | Description                      |
|----------------------|----------|------------|----------------------------------|
| `normal`             | int      | 10         | Queue size considered normal     |
| `warn`               | int      | 100        | Warning threshold                |
| `critical`           | int      | 1000       | Critical threshold               |
| `evaluationInterval` | Duration | 10 seconds | How often to evaluate queue size |

## Batching vs Non-Batching Mode

### Non-Batching (Default)

- Each store operation processed individually
- Lower latency (typically < 10ms)
- Best for moderate throughput (< 10K messages/sec)

```java
asyncStoreSettings(AsyncStoreSettings.builder()
    .

eventsBatching(0)  // Batching disabled by default (0)
    .

build())
```

### Batching Mode

- Store operations grouped and processed together
- Higher throughput (50K+ messages/sec possible)
- Slightly higher latency (bounded by `batchingFlushInterval`)
- **Requirements**: Wrapped store must implement `BatchingFixSessionMessagesStore`

```java
asyncStoreSettings(
        AsyncStoreSettings.builder()
            .

eventsBatching(100)                          // Batch up to 100 events
            .

batchingFlushInterval(Duration.ofMillis(50)) // Max 50ms when flushing waiting events in the queue
        .

build())
```

## Performance Characteristics

### Latency

- **Queue write**: < 1µs (lock-free, memory-mapped)
- **End-to-end (non-batching)**: 1-10ms (depends on wrapped store)
- **End-to-end (batching)**: 10-100ms (bounded by flush interval)

### Throughput

| Mode         | Reader Threads | Throughput (msgs/sec) | Notes                           |
|--------------|----------------|-----------------------|---------------------------------|
| Non-batching | 1              | 5,000 - 15,000        | Depends on wrapped store speed  |
| Non-batching | 4              | 20,000 - 50,000       | Multi-session parallelism       |
| Batching     | 2              | 50,000 - 200,000      | Requires batching-capable store |

### Resource Usage

- **Memory**: ~10MB + queue depth × message size
- **Disk I/O**: Sequential writes only (Chronicle Queue)
- **CPU**: Minimal (mostly waiting on wrapped store I/O)
- **Threads**: `readerThreadsCount` + 1 watchdog thread (if needed)

## Graceful Shutdown

The async store ensures all queued operations are flushed on shutdown:

```java
messagesStore.stop(Deadline.of(Duration.ofSeconds(30)));
```

**Shutdown behavior:**

1. Stops accepting new operations
2. Waits up to deadline for queue to drain
3. Logs warning if queue not empty at deadline
4. Stops background threads
5. Stops wrapped store

## Monitoring and Observability

see QueueSizeThresholdsListener interface and AsyncMessagesStoreSettings.queueSizeThresholdsListener()

### Check Queue Status

```java
AsyncMessageStore sessionStore = (AsyncMessageStore) messagesStore.getStore(sessionId);

long offersCount = sessionStore.getQueueOffersCount();  // Total messages queued
long pollsCount = sessionStore.getQueuePollsCount();    // Total messages processed
boolean isEmpty = sessionStore.hasEmptyQueue();          // Is queue empty?
```

## When to Use Async Store

**Use async store when:**

- ✅ Wrapping a slow store with blocking IO (JDBC, network storage)
- ✅ Minimizing FIX engine thread latency is critical
- ✅ You need fault tolerance for temporary storage outages
- ✅ Processing high message volumes (> 5K msgs/sec)

**Don't use async store when:**

- ❌ Wrapped store is already very fast (< 100µs, e.g., memory store)
- ❌ You need immediate consistency guarantees for queries
- ❌ Disk space is extremely limited (Chronicle Queue requires space)
- ❌ You can't tolerate any additional operational complexity

## Example: Complete Setup

```java
// Create DataSource with connection pooling
HikariConfig hikariConfig = new HikariConfig();
hikariConfig.

setJdbcUrl("jdbc:postgresql://localhost:5432/fix");
hikariConfig.

setMaximumPoolSize(10);

DataSource dataSource = new HikariDataSource(hikariConfig);

// Configure JDBC store
JdbcMessageStoreSettings jdbcSettings = JdbcMessageStoreSettings.builder()
        .dataSource(dataSource)
        .build();

// Wrap with async store
AsyncMessagesStoreSettings asyncSettings = AsyncMessagesStoreSettings.builder()
        .asyncStoreSettings(AsyncStoreSettings.builder()
                .asyncQueueDirectory("/dev/shm/staffix")
                .readerThreadsCount(2)
                .eventsBatching(50)
                .batchingFlushInterval(Duration.ofMillis(100))
                .queueSizeThresholdsSettings(AsyncStoreSettings.QueueSizeThresholdsSettings.builder()
                        .warn(500)
                        .critical(2000)
                        .build())
                .build())
        .wrappedFixMessagesStoreSettings(jdbcSettings)
        .underlyingStoreResourceWatchTaskCheckDelay(Duration.ofSeconds(10))
        .build();
```

## Troubleshooting

### Queue keeps growing

**Symptom**: Queue size increases continuously and never drains.

**Possible causes:**

1. Wrapped store is too slow for the message rate
    - **Solution**: Increase `readerThreadsCount` or optimize wrapped store or put throttling limits at the FIX
      application level
2. Batching not enabled but supported by wrapped store
    - **Solution**: Enable batching with `eventsBatching > 0`
3. Underlying storage is unavailable
    - **Solution**: Check wrapped store's `isUnderlyingStorageResourceAvailable()` and fix storage

### High latency

**Symptom**: End-to-end latency is higher than expected.

**Possible causes:**

1. Batching flush interval too high
    - **Solution**: Reduce `batchingFlushInterval` (trade throughput for latency)
2. Not enough reader threads
    - **Solution**: Increase `readerThreadsCount`
3. Chronicle Queue disk I/O slow
    - **Solution**: Use faster disk (SSD) or increase `rollCycleProvider` frequency

### Messages lost after crash

**Symptom**: Messages stored before crash are not in wrapped store after restart.

**Possible causes:**

1. Chronicle Queue directory deleted or corrupted
    - **Solution**: Ensure `chronicleDirectory` is on persistent storage
2. `flushPendingMessagesOnStartupDelay` too short
    - **Solution**: Increase delay to allow full flush on startup

## Related Modules

- [JDBC Store](../jdbc/jdbc-store/README.md) - Database-backed message store (wrappable)
- [File Store](../file/README.md) - File-based message store (wrappable)
- [Memory Store](../memory/README.md) - In-memory message store