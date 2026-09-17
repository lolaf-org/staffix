# Threading model

Almost everything your application code sees happens on **one thread per session: the session's I/O thread**. Read
that sentence twice: most of the rules below follow from it, and most bugs come from assuming otherwise.

---

## The threads in a running engine

| thread | how many | what runs on it |
|--------|----------|-----------------|
| **I/O worker** | per thread group, `ioThreadCount` (default 1) | the selector loop; reading, parsing, your decoders, your `FixApplication` callbacks, encoding, writing |
| **message executor** | `executorsThreadsCount` (default 1) | only work you explicitly hand to a `MessageExecutor` |
| **scheduler** | one `ScheduledExecutorService` | heartbeats, timeouts, session schedules, cancel-on-disconnect timers |
| **async store / logger consumer** | per async wrapper | the wrapped store's or logger's actual I/O |
| **async plugin consumer** | `consumerThreadPoolSize` (default 1) | replayed monitoring callbacks |

A session is bound to exactly one I/O worker for its lifetime, so *its* callbacks are single-threaded even when the
group has several threads. Sessions are assigned to workers by an `IOWorkerLoadBalancer`
(`MinRegisteredSessionLoadBalancer` by default), and `ioWorkersRebalanceInterval` can migrate them later.

```java
IOWorkersGroupSettings.builder()
        .ioThreadGroup(IOWorkersGroupSettings.IOThreadGroup.builder()
                .name("default")
                .ioThreadCount(4)
                .selectStrategy(new WakeupSelectStrategy(10))
                .build())
        .build().newInstance()
```

---

## What runs on the session's I/O thread

Everything on the message path:

- `FixMessageDecoder.mapFieldsForDecoding` and **`onDecoded`**
- `FixApplication.onLogon`, `onLogout`, `onSessionCreated`, and the rest of the callbacks
- encoding, and the write to the socket
- `MessageSendOperationCallback`
- session plugin callbacks, unless wrapped in the async plugin

Which gives one rule with two faces:

**Never block on this thread.** No database calls, no locks that another thread can hold, no network I/O of your own,
no unbounded loops. The javadoc for the send callbacks says it outright: *"any blocking IO methods calls in this
callback should be completely avoided to avoid destroying IO thread throughput"*. You are not slowing down one
session; you are stopping every session that worker owns.

**But you may reuse state freely.** Because it is the same thread every time, a decoder's fields, a reusable encoder
and any per-session scratch space need no synchronisation at all. That is why `asReusable()` is safe inside
`onDecoded`, and it is most of where the engine's speed comes from.

---

## Sending, and the one asymmetry worth knowing

`send()` behaves differently depending on where you call it from:

> Send the FixMessageEncoder immediately if the method is called within the IO thread, or enqueue the encoder to be
> processed and sent over the wire by the IO thread.

**On the I/O thread** the message is encoded and written inline. Nothing is queued, so a reusable encoder is free
again as soon as `send` returns, and the quickstart's acceptor relies on exactly this.

**Off the I/O thread** the call enqueues a write task. Two consequences:

1. The encoder is **not** free when `send` returns; it is read later, on the I/O thread. Reusing a single encoder
   instance across threads is a race. Use `newEncodersPool(id, multiThreadedBorrows = true, …)` instead.
2. The queue is bounded, by `getWriteTasksQueueCapacity()`. When it is full, **your thread blocks** until the I/O
   thread drains it. That is backpressure, and it is deliberate, but it means a slow socket can stall your producer.

`send` and `bufferize`/`flush` are documented as safe to call from any thread; the encoder you hand them is what
needs care.

### Getting onto the I/O thread deliberately

```java
session.processTask(() -> { /* runs on this session's I/O thread */ });
```

Use it when you need to touch per-session state that the I/O thread owns, rather than reaching into it from outside.

### Batching

`bufferize(...)` accumulates messages and `flush()` writes them in one go: better use of the TCP window at the cost
of slightly higher latency for the earlier messages. Prefer it to calling `send` in a loop.

---

## Getting work off the I/O thread: `MessageExecutor`

When processing genuinely costs too much to sit on the I/O thread, hand it to an executor **without losing
ordering**:

The routing key is a **namespace plus an index**. All tasks for the same key run on the same thread, in order, so
per-symbol or per-account ordering is preserved while unrelated keys proceed in parallel. The namespace lets the same
index mean different things in different subsystems without colliding.

The index must start at zero and increment by one per distinct value, and you do not have to compute it yourself:
`indexer` assigns a dense index to a field's values as they are decoded:

```java
public void mapFieldsForDecoding(FixFieldsDecoderMapper mapper, FieldsRegistry fieldsRegistry) {
    mapper // …
           .indexer(Symbol.get(), this::setSymbolIndex);
}

public void onDecoded(FixSession session, boolean possDupFlag, boolean possResend) {
    MessageExecutor<Quote, FixSessionId, Void, Void> exec =
            session.getMessageExecutor(Quote.class, symbolIndex);
    boolean enqueued = exec.execute(this::process, quote, session.getFixSessionId(), null, null);
    if (!enqueued) {
        // queue full: fall back to the blocking overload
        exec.execute(this::process, quote, session.getFixSessionId(), null, null, idleStrategy);
    }
}
```

Handing a task over allocates nothing, which is what makes this usable on the message path at all.

Two `execute` overloads, and the difference matters under load:

| overload | when the queue is full |
|----------|------------------------|
| `execute(processor, m, p1, p2, p3)` → `boolean` | **rejects**, returns `false`, and the decision is yours |
| `execute(processor, m, p1, p2, p3, queueFullIdleStrategy)` | **blocks**, applying the idle strategy between attempts |

Try the non-blocking one first and fall back, as above: that way a full queue costs you a branch rather than
stalling the I/O thread by default.

Call `release()` when a routing key is finished with, or you leak executors; `execute` on a released instance throws
`IllegalStateException`.

A complete worked version is in
[`MarketDataStreamerExample`](../examples/staffix-api-examples/src/main/java/org/lolaf/staffix/examples/MarketDataStreamerExample.java),
which routes market data per symbol.

```java
MessageExecutorSettings.builder()
        .executorsThreadsCount(4)
        .queueSizePerThread(64)
        .idleStrategy(BusySpinIdleStrategy::getInstance)
        .build()
```

Defaults: one thread, 64 tasks per thread, `WaitNotifyIdleStrategy`.

---

## Plugins

A `FixSessionsPlugin` is asked, per session, for a plugin instance, and **must return a dedicated instance for each
session**; shared instances are not supported. That is the same bargain as everywhere else: because the instance
belongs to one session, its callbacks are single-threaded and it can hold mutable state without locks.

Wrapping a plugin in the async wrapper moves its callbacks to a consumer pool, which changes the bargain: the
delegate's callbacks then run on a pool thread, not the session's. Since each session's queue is drained by one
consumer, per-session ordering survives; per-session state remains safe, cross-session state does not.

The exception to "one session, one thread" is the pair of encoding callbacks that fire on the **producing**
thread (`getMessageEncodingToken` and `onMessageEncodingStarted`), which may run concurrently and must be
thread-safe. See [Session plugins](session-plugins.md#which-thread-you-are-on).

---

## Stores and loggers

By default a store or logger runs **on the session's I/O thread**, which is why a synchronous JDBC store puts a
network round trip inside your FIX round trip. The async decorators move that work to their own consumer threads;
see [Stores and loggers](stores-and-loggers.md#taking-io-off-the-session-thread).

---

## Choosing thread counts

**I/O threads.** One thread can serve many sessions; the question is how much CPU each session's parsing and
application code needs. Start at 1 and add threads when a worker is saturated, not before. If you busy-spin, every
I/O thread costs a full core; see [Tuning for latency](tuning-for-latency.md#2-choose-where-the-cpu-goes).

**Thread groups** exist so different sessions can get different treatment: a busy-spinning group for the sessions
that matter, a blocking one for the rest, on the same engine.

**Message executor threads.** Only useful if you actually route work to them. One is plenty otherwise.

---

## The rules, in one place

1. Never block the I/O thread.
2. On the I/O thread, reuse everything; off it, share nothing.
3. A reusable encoder is safe on the I/O thread and unsafe across threads, so use a pool with
   `multiThreadedBorrows = true`.
4. `send` off the I/O thread can block when the write queue is full.
5. Use `processTask` to get onto the session's thread; use `MessageExecutor` to get off it while keeping order.
6. Per-session plugin state is safe. Cross-session state never is.
