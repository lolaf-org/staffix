# Tuning the async queue

The async store and the async logger ([Stores and loggers](stores-and-loggers.md#taking-io-off-the-session-thread))
hand every write to a Chronicle Queue and return. That append is all the session thread pays for, so it is the part
worth tuning. Both wrappers take the same `AsyncStoreSettings`, and everything on this page applies to both.

Each session gets its own queue, a subdirectory of `asyncQueueDirectory`, written as one file per roll cycle. A file
is deleted once the background thread has read past it and Chronicle has moved on to the next one.

---

## Where to put the queue

The directory decides what survives and what an append costs.

| queue on                  | survives a process crash | survives a reboot | survives a power loss | p99.9 append, pretouching on |
|---------------------------|--------------------------|-------------------|-----------------------|------------------------------|
| disk                      | yes                      | yes               | no                    | 40 to 45×                    |
| tmpfs                     | yes                      | no                | no                    | about 75×                    |
| **tmpfs with huge pages** | yes                      | no                | no                    | **25 to 30×**                |

The last column is the slowest append in a thousand, as a multiple of the typical call of the file store without
sync, whose own p99.9 is about 10×. The full figures are under [Measured](#measured).

A queue is never synced per write, whatever the file system: Chronicle's `syncMode`, which `builderConfigurer` can
set, only syncs a piece of the file when Chronicle stops using it. So a queue on disk is as durable as the
unsynced file store, and no more.

**A queue on tmpfs is held in RAM.** The file of the current roll cycle keeps every message appended during the
cycle, and the previous one stays until it is read past, so plan for up to two cycles of messages per session. With
the default hourly cycle, 300-byte messages at 20,000 per second fill about 21 GB an hour. Size the mount for it, and
shorten the cycle with `rollCycleProvider` (for example `RollCycles.FIVE_MINUTELY`) when the rate is high.

---

## Huge pages on tmpfs

A tmpfs hands out memory in 4 KB pages unless it is mounted with huge pages, 2 MB each. Huge pages mean fewer TLB misses for
the thread appending and the thread reading, which appears to be what is left of the slowest appends once pretouching
takes the page faults off the session thread. Mounting needs root:

```bash
sudo mkdir -p /var/lib/my-app/queues
sudo mount -t tmpfs -o huge=within_size,size=8g,uid=$(id -u),mode=0700 tmpfs /var/lib/my-app/queues
```

and to mount it at every boot, a line in `/etc/fstab`, with the uid of the user running the engine:

```
tmpfs  /var/lib/my-app/queues  tmpfs  huge=within_size,size=8g,uid=1000,mode=0700  0  0
```

`huge=within_size` and `huge=always` both work; `huge=advise` gives the queue nothing, since Chronicle never asks for
huge pages. `/sys/kernel/mm/transparent_hugepage/shmem_enabled` overrides every mount when it is set to `deny` (no
tmpfs gets huge pages) or `force` (every tmpfs does), so check it is neither.

**Use huge pages only with pretouching on.** Each new huge page is zeroed when it is first touched, 2 MB at once:
the pretouch thread pays for that out of sight, but without it the session thread does, and the rarest appends
become about three times slower than on a plain tmpfs (see [Measured](#measured)).

**Huge pages are best effort.** When memory is fragmented the kernel quietly falls back to 4 KB pages. `ShmemHugePages`
in `/proc/meminfo` shows how much tmpfs memory is in huge pages, and `ShmemPmdMapped` in `/proc/<pid>/smaps` how much of
the engine's mappings are.

Huge pages do not reach a queue on disk: ext4 maps its files in 4 KB pages.

---

## Pretouching

An append-only file keeps writing to memory nobody has touched yet, so without help every new page faults on the
session thread: on tmpfs about once every 4 KB, on disk less often but at the price of a block allocation. Staffix
runs one thread per async store or logger, `Staffix-async-queue-pretoucher-<instanceId>`, that touches the pages ahead of
each queue's writer so they are ready when it gets there. It is on by default.

| setting                 | default | what it is for                                                                                         |
|-------------------------|---------|--------------------------------------------------------------------------------------------------------|
| `pretouchInterval`      | 1 ms    | how often the thread goes over every queue; `Duration.ZERO` turns pretouching off and starts no thread |
| `pretouchAheadTime`     | 40 ms   | how far ahead of the writer to touch, given as time: touch what the queue will write in the next 40 ms |
| `pretouchMinAheadBytes` | 1 MB    | the least distance ahead, which covers a burst after an idle spell, before the rate is measured        |

The distance follows each queue's own rate, so an idle session keeps 1 MB ready and a busy one more. It is capped at
half the queue file's chunk size, 32 MB by default; to go further, raise `blockSize` through `builderConfigurer`.

**What it costs:** one thread waking every millisecond, and memory provided ahead of when the queue needs it. Touching
a page never changes a byte of it, so it is safe while the writer fills the same page.

**A busy disk undoes it.** When other processes write heavily to the same disk, the kernel writes the touched pages
back early and protects them again, so the session thread faults on them anyway, at times more often than without
pretouching. A queue whose latency matters belongs on tmpfs, or on a disk nothing else writes much to.

**When to raise `pretouchAheadTime`.** A session with [monitoring](monitoring.md#the-async-store-and-loggers-meters)
counts the pages its writer reached before the pretouch thread, each a page fault on the session thread:
`async.store.pretouch.missed.pages` for the store and `async.logger.pretouch.missed.pages` for the logger. Without
monitoring, each queue logs its run's count when it stops:

```
Persistent queue for FIX session ... is stopped with 0 unprocessed elements and 0 pages the writer reached before the pretoucher
```

A count that keeps rising means the pretouch thread falls behind. It is held up from time to time for much longer
than its interval, and `pretouchAheadTime` has to outlast those pauses: raise it.

**At very high rates.** The thread touches at most 256 KB per queue each time it wakes, so at the default interval one
queue can be kept ahead up to about 256 MB per second, 250,000 messages of 1 KB. Past that, shorten `pretouchInterval`.
One thread serves every queue of the store, so several busy queues share that budget.

**Turning it off** (`pretouchInterval(Duration.ZERO)`) puts the page faults back on the session thread. Do it to save the
thread when the store's latency does not matter, and then keep the queue off huge pages.

### The startup warning

When a queue starts on tmpfs, Staffix reads the mount and logs one warning per directory if it is set up against the
pretouching:

- **tmpfs without huge pages while pretouching:** the appends take TLB misses huge pages would avoid. Mount it with
  `huge=within_size`, and set `shmem_enabled` to `never` if it is `deny`.
- **tmpfs with huge pages without pretouching:** each new huge page is zeroed on the session thread. Turn pretouching
  back on, or use a tmpfs without huge pages.

It only warns, since the queue works either way, and it checks nothing on disk or off Linux.

---

## Reading the queue

| setting                                   | default             | what it is for                                                                                    |
|-------------------------------------------|---------------------|---------------------------------------------------------------------------------------------------|
| `eventsBatching`                          | 0                   | above zero, the wrapped store or logger gets the queued writes that many at a time                |
| `batchingFlushInterval`                   | 100 ms              | the longest a write waits for a batch to fill                                                     |
| `readerThreadsCount`                      | 1                   | threads reading the queues and calling the wrapped store or logger; sessions are spread over them |
| `readerThreadsIdleStrategy`               | backoff, up to 5 ms | what a reader thread does when its queues are empty                                               |
| `readerThreadMaxEventsProcessingPerStore` | 1024                | the most writes a reader thread takes from one queue before moving to the next, without batching  |

**Batching is what keeps a slow wrapped store up with the session.** One write at a time, a synced disk store drains
far fewer messages per second than a busy session sends, and the queue grows without end. With batching it keeps
up, and the session thread's typical append costs less.

**One reader thread is enough while the wrapped store keeps up.** Add threads when several sessions write to a slow
store and the queue depth keeps rising: the async store's probe raises an alert at the depths set in
`queueSizeThresholdsSettings`; see [Alerting](alerting.md#built-in-probes).

---

## Every setting

The other settings of `AsyncStoreSettings`, beyond the two tables above:

| setting                       | default                                                               | what it is for                                                                         |
|-------------------------------|-----------------------------------------------------------------------|----------------------------------------------------------------------------------------|
| `asyncQueueDirectory`         | none, required                                                        | where each session's queue goes; see [Where to put the queue](#where-to-put-the-queue) |
| `rollCycleProvider`           | `RollCycles.FAST_HOURLY`                                              | how long a queue file covers, per session                                              |
| `builderConfigurer`           | does nothing                                                          | any other Chronicle queue setting, per session, such as `blockSize`                    |
| `threadFactory`               | `FastThreadLocalThread::new`                                          | creates the reader and pretouch threads                                                |
| `queueSizeThresholdsSettings` | warning at 250, major at 1,000, critical at 5,000, checked every 10 s | the queue depths at which the probe raises its alert                                   |

**In Spring Boot**, each async store or logger instance takes these settings as properties, in kebab case:
`async-queue-directory`, `events-batching`, `batching-flush-interval`, `reader-threads-count`,
`reader-thread-max-events-processing-per-store`, `pretouch-interval`, `pretouch-ahead-time`,
`pretouch-min-ahead-bytes`, and `roll-cycle`, a `RollCycles` name applied to every session:

```yaml
staffix:
  messages-stores-async:
    instances:
      MAIN:
        wraps: MAIN
        async-queue-directory: /var/lib/my-app/queues
        roll-cycle: five-minutely
        events-batching: 100
```

`builderConfigurer`, `threadFactory`, `readerThreadsIdleStrategy` and `queueSizeThresholdsSettings` are set in Java
only, through `AsyncStoreSettings.builder()`.

---

## Measured

Each figure says how many times longer the session thread's call takes than a typical call (p50) of the file store
without sync: 3× is three times as long, so higher is slower. This is the unit of
[Message stores](performance.md#message-stores), with the benchmark harness's own cost subtracted. The async store is
measured in front of a store that drops every write, so these rows show the queue alone.

| queue on              | pretouching | batching | typical call (p50) | p99       | p99.9      | p99.99       |
|-----------------------|-------------|----------|--------------------|-----------|------------|--------------|
| tmpfs with huge pages | on          | yes      | about 3×           | about 8×  | about 25×  | about 220×   |
| tmpfs with huge pages | on          | no       | about 7×           | about 10× | about 30×  | about 200×   |
| disk                  | on          | yes      | about 3×           | about 9×  | about 40×  | about 350×   |
| disk                  | on          | no       | about 6×           | about 11× | about 45×  | about 300×   |
| tmpfs                 | on          | yes      | about 3×           | about 9×  | about 75×  | about 240×   |
| tmpfs                 | on          | no       | about 4×           | about 9×  | about 75×  | about 260×   |
| tmpfs                 | off         | yes      | about 3×           | about 40× | about 80×  | about 2,200× |
| tmpfs                 | off         | no       | about 7×           | about 45× | about 80×  | about 2,600× |
| tmpfs with huge pages | off         | yes      | about 3×           | about 9×  | about 35×  | about 6,700× |
| tmpfs with huge pages | off         | no       | about 8×           | about 15× | about 60×  | about 6,600× |
| disk                  | off         | yes      | about 3×           | about 11× | about 640× | about 1,100× |
| disk                  | off         | no       | about 6×           | about 13× | about 650× | about 1,000× |

For scale, the file store without sync is 1× at p50 by definition, about 5× at p99 and about 10× at p99.9.

What it shows:

- **Pretouching takes the page faults out of the slow calls.** Without it, tmpfs pays a page fault at the p99 (40 to
  45×, against about 9× with it) and disk a block allocation at the p99.9 (about 650×, against 40 to 45×). Even with
  it, the queue's p99.9 stays above the file store's: 25 to 75×, against about 10×.
- **Huge pages are worth it only with pretouching.** With it they cut the p99.9 by about three on tmpfs. Without it they
  fix the p99, because faults become rare, but the rarest calls each zero a huge page and are about three times slower
  than on a plain tmpfs.
- **With pretouching, disk beats a plain tmpfs at the p99.9** and loses only to tmpfs with huge pages.
- **Batching lowers the typical call**, to half or less except on a plain tmpfs. Without batching, the typical call
  is higher on tmpfs with huge pages than on the other file systems; unexplained so far.

<sub>JMH sample mode at 20,000 messages per second, 300-byte messages, pacing outside the timed call, pinned to six
cores; disk is ext4 on NVMe, tmpfs is `/dev/shm`, tmpfs with huge pages is a tmpfs mounted with `huge=within_size`, on
which the kernel gave the queue huge pages for only part of its memory. Pretouching at its defaults. Ratios are
rounded and moved by up to 2× between runs on the same machine. Raw results:
[`NoOp`](../benchmarks/results/jmh-result-NoOpMessagesStoreBenchmark-2026-11-15.json),
[`File`](../benchmarks/results/jmh-result-FileMessagesStoreBenchmark-2026-11-15.json),
[`Async`](../benchmarks/results/jmh-result-AsyncMessagesStoreBenchmark-2026-11-15.json).</sub>
