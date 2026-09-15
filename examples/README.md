# Staffix examples

Runnable programs, smallest first. Every one of them starts both sides of a FIX session in a single process, so
there is nothing to install and nothing to connect to — run it and watch a session come up.

All commands are run **from the repository root**.

---

## Start here

### `quickstart` — the smallest complete Staffix application

An acceptor and an initiator, a QuoteRequest sent, a Quote sent back, and both printed. Just under 300 lines with
nothing hidden in a base class: this is the whole of what a first Staffix program needs.

```bash
mvn compile exec:java -pl examples/quickstart \
    -Dexec.mainClass=org.lolaf.staffix.examples.quickstart.QuickstartExample
```

Expected output:

```
initiator: initiator-session:VERSION_44:initiator->acceptor logged on, sending a QuoteRequest
acceptor: acceptor-session:VERSION_44:acceptor->initiator logged on
acceptor: QuoteRequest quickstart-1 for EUR/USD
initiator: Quote quickstart-1-1 bid 101.25
```

> **Do not add `-q`.** `exec:java` runs inside Maven's own JVM, so Maven's SLF4J provider handles the application's
> logging and `-q` hides every INFO line the example prints — including all of the output above.

There is also a [`QuickstartExample.sh`](quickstart/QuickstartExample.sh), the same launcher the other examples have.
It runs a plain `java` with every [JVM flag](#jvm-flags) already set, on the shaded jar:

```bash
mvn package -pl examples/quickstart -am
cd examples/quickstart
./QuickstartExample.sh
```

Read it in this order: the engine wiring in `main`, then `sessionSettings`, then the two `FixApplication`
implementations. The interesting part is the decoder — fields are mapped onto setters, and anything you do not map
is never parsed.

Then run [`SessionLifecycleExample`](#the-rest) for the other half of the picture: the same two sides, no business
message at all, and every `FixApplication` callback logged as a session comes up, is logged out by its schedule,
comes back and is shut down — fifteen seconds end to end.

---

## The rest

The remaining examples share [`examples-core`](examples-core), which handles command-line options, multi-client
setup, optional persistence and profiling. They are demonstrations rather than starting points — read `quickstart`
first.

They are [picocli](https://picocli.info) commands, so `--help` lists the options each one accepts.

| example | what it shows | script |
|---------|---------------|--------|
| [`staffix-api-examples`](staffix-api-examples) → `SessionLifecycleExample` | every `FixApplication` callback a session goes through, driven by a schedule that opens and closes while you watch | [`SessionLifecycleExample.sh`](staffix-api-examples/SessionLifecycleExample.sh) |
| [`staffix-api-examples`](staffix-api-examples) → `QuoteRequestExample` | quote request/response streaming, user-defined fields, cached and thread-local field decoding | [`QuoteRequests.sh`](staffix-api-examples/QuoteRequests.sh) |
| [`staffix-api-examples`](staffix-api-examples) → `TradingExample` | NewOrderSingle / ExecutionReport, the order-lifecycle path | [`TradingExample.sh`](staffix-api-examples/TradingExample.sh) |
| [`staffix-api-examples`](staffix-api-examples) → `MarketDataStreamerExample` | MarketDataSnapshotFullRefresh streaming, the highest-volume path | [`MarkedDataStreamer.sh`](staffix-api-examples/MarkedDataStreamer.sh) |
| [`plugin-api`](plugin-api) | writing a session plugin: a user-defined field stamped onto every outbound Email, with both applications unaware of it | [`PluginApiExample.sh`](plugin-api/PluginApiExample.sh) |
| [`file-session-settings`](file-session-settings) | sessions declared in YAML files instead of in code, with the generated JSON schema | [`FileSessionSettingsExample.sh`](file-session-settings/FileSessionSettingsExample.sh) |
| [`spring-boot-starter-example`](spring-boot-starter-example) | acceptor and initiator declared entirely in `application.properties`; you supply only the `FixApplication` beans | [`SpringBootExample.sh`](spring-boot-starter-example/SpringBootExample.sh) |
| [`advanced-monitoring`](advanced-monitoring) | metrics, logs and traces exported over OTLP — pair it with the [Grafana stack](../monitoring/grafana) | [`MetricsLogsTracesExample.sh`](advanced-monitoring/MetricsLogsTracesExample.sh) |

`SessionLifecycleExample` is the one to run after `quickstart`, and the only one of these that is about the session
rather than about messages: it sends no business message at all, implements every `FixApplication` callback with a
single log line, and lets a schedule drive the session through its whole life in about fifteen seconds. The trick is
that the schedule is not configured trading hours — it is **computed from the wall clock when the example starts**, a
window opening two seconds from now and closing five seconds later, then a second one after a three-second gap
(`-w`, `-g` and `-u` change those three numbers). Everything the log shows follows from it: the engine logs the
session on when the window opens, logs it out when it closes, brings it back when it reopens, and the run ends with
the engine being stopped while the session is up, so the shutdown path is in the output too.

```
12:00:17.083 [acceptor ] setup: decoders are asked for on acceptor-session-1, before anything else. This application maps none
12:00:17.085 [acceptor ] onSessionCreated: acceptor-session-1 exists and its plugins are up, nothing is connected yet
12:00:17.125 [initiator] setup: decoders are asked for on initiator-session-1, before anything else. This application maps none
12:00:17.125 [initiator] onSessionCreated: initiator-session-1 exists and its plugins are up, nothing is connected yet
12:00:19.171 [initiator] onInsideSessionTime: the schedule window is open, the session may come up
12:00:19.189 [acceptor ] onFixSessionAccepted: a connection is bound to acceptor-session-1
12:00:19.191 [acceptor ] validateLogon: the peer's Logon is in, credentials would be checked here
12:00:19.193 [acceptor ] onLogon: the session is usable, sequence numbers are settled
12:00:19.193 [initiator] onLogon: the session is usable, sequence numbers are settled
12:00:20.006 [initiator] onHeartbeat: sent by the peer at 2026-08-17T10:00:20.003679Z
        ... a heartbeat a second, each with the onAdminMessageEncoding hook that let fields be added to it ...
12:00:22.506 [acceptor ] onPreOutsideSessionTime: the window closes in 773ms
12:00:23.502 [initiator] onOutsideSessionTime: the window is closed, the session logs out and stays down until it reopens
12:00:23.502 [initiator] onLogoutInitiated: this end is sending a Logout - 'Outside of session timeframe'
12:00:23.506 [acceptor ] onLogout: logged out - 'Outside of session timeframe' (Logout received)
12:00:23.511 [initiator] onDisconnected: the connection is gone
12:00:26.522 [initiator] onInsideSessionTime: the schedule window is open, the session may come up
12:00:26.525 [acceptor ] onLogon: the session is usable, sequence numbers are settled
        ... up again, until the engine is stopped ...
12:00:31.325 [initiator] onSessionPreDestroy: the session is going away; it may still be logged in, so a last message could go out here
12:00:31.325 [initiator] onLogoutInitiated: this end is sending a Logout - 'Fix initiator stop'
12:00:31.327 [initiator] onLogout: logged out - 'Fix initiator stop' (Logout received)
12:00:31.328 [acceptor ] onDisconnected: the connection is gone
12:00:31.380 [acceptor ] onSessionDestroyed: logged out and past sending anything
12:00:31.390 [acceptor ] destroy: the application itself is being discarded
```

Two things that output teaches by omission. There is no `onConnected` on `FixApplication`: the TCP connection is not
a session-layer event, an acceptor learns of it through `FixSessionEventsListener.onFixSessionAccepted` (logged above
next to the application callbacks) and an initiator only ever sees the logon that follows. And the engine says
`has no incoming FixMessageDecoder` once per session on the way up, because this application maps none — expected
here, where session-level messages are the whole subject, and a sign of a missing decoder anywhere else.

Unlike the others it does not wait for a keypress before starting, and ignores `-d`: its windows are computed at
startup, so a run held at a prompt would begin with its schedule already in the past.

`plugin-api` is the one to read if you want to *extend* the engine rather than use it: `StampingFixSessionsPlugin`
is a complete `FixSessionsPlugin`, registration and all, in about a hundred lines. It goes with the
[Session plugins](../docs/session-plugins.md) guide.

`staffix-api-examples` also ships [`ToastMyCpuExample.sh`](staffix-api-examples/ToastMyCpuExample.sh): `TradingExample`
with the message store and logger set to `VOID` and low-latency mode on, which is the configuration to run when you
want the engine flat out rather than readable.

### Running them

The shell script is the easy way — it is a plain `java` launch that already carries every JVM flag from the
[section below](#jvm-flags), so there is nothing to remember. It expects the shaded jar the module's `package` phase
produces, and runs from the module's own directory:

```bash
mvn package -pl examples/staffix-api-examples -am
cd examples/staffix-api-examples
./TradingExample.sh --help
```

Anything you append is passed straight through to the example, so the picocli options work as usual.

`spring-boot-starter-example` is the exception: its jar is repackaged by the spring-boot plugin at the `install`
phase, so build it with `mvn install -pl examples/spring-boot-starter-example -am`, and its arguments
are Spring's rather than picocli's.

Or, without packaging, straight from the repository root — but this runs in Maven's own JVM, so it gets none of
those flags:

```bash
mvn compile exec:java -pl examples/staffix-api-examples \
    -Dexec.mainClass=org.lolaf.staffix.examples.TradingExample
```

---

## JVM flags

The examples run without any, and the quickstart above was run without any — but it says so on the way up:

```
Missing JVM arg --enable-native-access=ALL-UNNAMED to enable @Contended to work with AbstractRingBuffer
Missing JVM arg -XX:-RestrictContended to enable @Contended to work with AbstractRingBuffer
```

For anything you intend to measure, use the full set — the same one every `*.sh` launcher next to the examples
already passes:

```
-XX:+UnlockDiagnosticVMOptions \
-XX:+DebugNonSafepoints \
-XX:-RestrictContended \
-XX:ContendedPaddingWidth=64 \
--enable-native-access=ALL-UNNAMED \
--add-opens java.base/jdk.internal.ref=ALL-UNNAMED \
--add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
--add-opens java.base/java.lang.reflect=ALL-UNNAMED \
--add-opens java.base/sun.nio.ch=ALL-UNNAMED \
```

| flag | what it is for |
|------|----------------|
| `-XX:+UnlockDiagnosticVMOptions` | required to unlock `DebugNonSafepoints` |
| `-XX:+DebugNonSafepoints` | makes the JIT keep debug info away from safepoints, so a sampling profiler (`-pe`) attributes samples to the frames that really ran rather than to the nearest safepoint |
| `-XX:-RestrictContended` | lets `@Contended` apply outside the JDK's own classes — without it the annotation is silently ignored on the ringos and Staffix structures |
| `-XX:ContendedPaddingWidth=64` | sizes that padding to one cache line, which is what keeps those structures off each other's lines |
| `--enable-native-access=ALL-UNNAMED` | allows the native/`Unsafe` access the memory-access layer performs, instead of warning about it |
| `--add-opens java.base/jdk.internal.ref` | direct-`ByteBuffer` cleaner access, used by the JDK 11–14 memory-access provider |
| `--add-opens java.base/jdk.internal.misc` | `jdk.internal.misc.Unsafe`, behind the zero-allocation thread-local serdes; without it they still work, but allocate a fresh `String` per field and warn once at startup |
| `--add-opens java.base/java.lang.reflect` | reflective setup of the method handles those providers bind at class-init |
| `--add-opens java.base/sun.nio.ch` | betty's `SelectorOptimizer`, which replaces `SelectorImpl`'s selected-key `HashSet` with an array-backed set; without it the plain JDK selector is used |

None of them is mandatory: each one missing costs a warning and a slower path, never a failure.

You do not have to type any of this: the [`*.sh` launcher](#running-them) beside each example already passes the
list, which is the reason to prefer it over `exec:java` whenever the numbers matter. `exec:java` runs in Maven's own
JVM, so there the flags have to go in `MAVEN_OPTS` instead.
