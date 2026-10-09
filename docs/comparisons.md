# Compared to other FIX engines

## Compared to QuickFIX/J

QuickFIX/J is the default choice in Java FIX, for good reasons, and this section is not an argument that it is a bad
engine. It is a list of specific, checkable differences.

### Latency, and the garbage behind it

Half the round-trip time on identical settings, a tighter tail, and the difference that outlasts any one benchmark:
sixteen kilobytes of garbage per message against under one byte. The [numbers](performance.md#measured) come from a harness that starts both
engines the same way. Staffix additionally offers a busy-spin mode that QuickFIX/J has no equivalent of.

### Persistence and logging do not cost you the session thread

In QuickFIX/J both happen inline on the thread that is sending. `Session.persist` writes the message through
`MessageStore.set` before it goes out, and `Session.send` calls `Log.onOutgoing` before handing the bytes to the
responder, so with `FileStore` there is a `flush` on the send path, and an `fsync` per message if you asked for one.
Durability is bought with latency.

Staffix puts any store or logger behind an off-heap, memory-mapped Chronicle Queue handoff: the session thread neither
waits for the I/O nor allocates to hand it over. The file store and the message log stay on while the round trip stays
at 19.5 µs.

### What Staffix has that QuickFIX/J does not

Ordinary things to ask of a FIX engine, each of which you would otherwise build yourself.

- **Cancel-on-disconnect**: settings, wire fields and application callbacks, with the disconnect and the logout cases
  told apart (`CancelOnDisconnectType`).
- **Continuous line and clock measurement**: round-trip time and the remote-versus-local clock offset, per session,
  taken from the protocol's own TestRequest/Heartbeat exchange on a monotonic clock. No external probe, nothing
  proprietary on the wire, and an answer to the two questions a counterparty link actually raises: how long the line
  takes, and whether the two clocks agree. See [Network monitoring](network-monitoring.md).
- **Logon authentication that does not hold the I/O thread**: `validateLogon` returns a
  `CompletableFuture<Optional<String>>` and is handed an executor, so a directory lookup runs off the session's thread
  and the reject message comes back when it answers. A JAAS login module is one call away (`JaasHelper.jaasLogin`).
  QuickFIX/J authenticates by throwing `RejectLogon` out of `fromAdmin`, on the thread processing the message, so
  whatever your credential store takes is time the session spends blocked.
- **Obfuscation on the logging path**: a `LogObfuscator` decides what a logged message may carry, so account
  identifiers and client data need not land in the log verbatim. QuickFIX/J writes the raw message.
- **Settings reload without a restart**: `AdminApi.reloadFixSessionsSettingsStore` re-reads a settings store and adds
  or removes sessions accordingly. QuickFIX/J reads its settings when it starts.
- **Warmup before the open**: [`jvm-warmup`](../jvm-warmup) drives a full loopback session over a message carrying one
  field of every type the codec knows, so the whole path (parser, encoders, session state machine, and your own store,
  logger and plugins) is compiled before the first real order rather than the open being served interpreted.

### Configuration your editor checks

Staffix reads YAML into a typed settings model and ships a JSON schema generated from that model in the jar, so your
editor completes the file and marks what is wrong before the engine starts. QuickFIX/J's `.cfg` is a properties map read
key by key: a key it does not know is never read, so a typo leaves the default silently in place.

### Observability

Micrometer timers, OpenTelemetry tracing with W3C propagation, OTLP export and Grafana dashboards, each able to be
sampled or moved onto its own thread, because metrics are not worth latency. QuickFIX/J ships a JMX exporter and nothing
else in the engine itself.

### Extending it

Both engines let you write your own message store. Staffix also publishes the contract suite that holds yours to the
same standard as the ones that ship (`staffix-messages-store-test-kit`); QuickFIX/J's equivalent tests never leave its
own repository. Staffix adds session plugins (attach to every message of a session from outside the application, to
measure it, audit it or add a field on the way out) and an admin SPI, so the operational surface is not tied to JMX.

### FIX protocol conformance

Every build drives a real QuickFIX/J 3.0.2 engine against Staffix in-process, so if you are moving off QuickFIX/J, wire
compatibility with what your counterparties already speak is a test rather than a hope. Alongside it, **22** of the
official FIX Session conformance scenarios from `FIX_Session_Testcases_June_2020` run as executable tests.

### What QuickFIX/J has that Staffix does not

FIX 4.0 and 4.1, which Staffix does not ship: it starts at 4.2. Twenty years of production deployment across the
industry, a large community, a mature C++ sibling, far more integrations, and the accumulated battle-testing that only
time provides. Staffix is younger and narrower. If your constraint is ecosystem maturity rather than latency, that is a
real answer and QuickFIX/J is a reasonable one.


## Compared to Artio

QuickFIX/J is the popular engine. **Artio** is the serious one, and it is the comparison that matters if latency is why
you are reading this.

Artio came out of Real Logic, the authors of Aeron and Agrona, from the LMAX lineage that produced the Disruptor and,
with it, most of the ideas this whole field is built on. Adaptive Financial Consulting acquired Real Logic in 2022, and
the project is now developed at [`artiofix/artio`](https://github.com/artiofix/artio) under Apache 2.0, with releases on
Maven Central. It is a genuinely high-performance FIX engine, it is in production at demanding institutions, and on
allocation per message, the measurement that decides whether an engine can run a session without a GC pause in it, it is
Staffix's peer rather than its inferior. Anyone claiming otherwise has not measured it.

Where the two differ is architecture. Artio runs on Aeron: an engine process, a media driver, and an archive, with
sessions journalled through the Aeron log and inter-process traffic over Aeron channels. That design buys durability,
replay and multi-process scale-out as properties of the transport rather than as features bolted on, and it is why Artio
scales to session counts that a single-process engine cannot. It also means an Aeron media driver to deploy, tune and
operate, a log directory to size, and a round trip that crosses process boundaries even when both ends are on one host.

Staffix is a library, in your process, on your threads. There is no media driver, no archive, no second process: a FIX
session is objects on a heap and a socket in an NIO selector you configured. That is a smaller architecture with a
smaller operational surface, and in the [round-trip benchmark](performance.md#measured) it is the faster one: 19.5 µs against 40.4 at stock
settings, 11.2 against 29.7 when both are told to prioritise latency, with a 99th percentile nearly three times tighter
and a worst case twenty-five times tighter in that mode.

**That architecture also shows up long before you measure anything, in what it takes to get a session up.** Artio needs
an `ArchivingMediaDriver`, archive contexts and threading modes, **five Aeron channel URIs per side** (data, control
request, control response, replication, recording events), log directories to place and size, and, in our harness, a
priming round trip to stop a lost first message wedging the first measured iteration. None of it is gratuitous; it is
what running on Aeron requires, and every line of it is buying something. But it is the difference between adding a
dependency and adopting a platform, and that is worth knowing at the start rather than discovering halfway in. Staffix
and QuickFIX/J are both, in this respect, just jars.

Three honest qualifications on the latency numbers:

- **The low-latency configurations are not identical.** Staffix busy-spins; Artio yields. Busy-spinning buys latency by
  burning a core, and the comparison flatters whoever is allowed to spend more CPU.
- **Artio's architecture is in the measurement.** Its round trip includes the Aeron media driver hop that Staffix's does
  not have, because Artio does not have a mode without one. That is a real cost of the design, and it is also a real
  capability of the design that this benchmark gives it no credit for.
- **Tuning someone else's engine is hard.** These are our settings for Artio, not its maintainers'. If you know how to
  configure it better, the harness is in this repository and we would rather publish a corrected number than a
  flattering one: [`ArtioEngine`](../benchmarks/src/main/java/org/lolaf/staffix/benchmarks/impl/ArtioEngine.java).

Choose Artio if you need durable replay in the transport, many sessions across processes, or the Aeron stack you are
already running. Choose Staffix if you want a FIX engine that is a dependency rather than an infrastructure component,
and the lowest round-trip latency of the three measured here.
