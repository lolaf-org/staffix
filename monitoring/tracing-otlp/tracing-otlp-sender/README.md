# `tracing-otlp-sender`

Makes OpenTelemetry's OTLP/HTTP exporters publish through this library's own HTTP clients, instead of through one of
their own.

---

## What it is

OpenTelemetry resolves its HTTP transport through a `ServiceLoader`, over the
`io.opentelemetry.sdk.common.export.HttpSenderProvider` SPI. This module registers
[`StaffixHttpSenderProvider`](src/main/java/org/lolaf/staffix/monitoring/tracing/otlp/sender/StaffixHttpSenderProvider.java)
against it, so the span exporter ends up on the same
[`HttpSender`](../../../api/src/main/java/org/lolaf/staffix/api/http/HttpSender.java) that the OTLP message logger and
the OTLP meter registry already use. Putting the module on the classpath is the whole configuration.

That SPI is supported API, not an internal one — it lives in `opentelemetry-sdk-common` and carries no
`@ApiStatus.Internal`, and `opentelemetry-exporter-sender-jdk` is a second in-tree implementation of it.

**It replaces `opentelemetry-exporter-sender-okhttp` rather than sitting beside it.** With two providers registered,
OpenTelemetry logs *"Multiple HttpSenderProvider found"* and takes whichever it finds first — so the client sending
your telemetry would depend on classpath order. `tracing-otlp-impl` therefore excludes OTel's OkHttp sender, which is
also what takes `okhttp-jvm` and the 1.7 MB `kotlin-stdlib` off the classpath of everything that traces.

Three things the adapter does that are not obvious from the interfaces:

- **It retries, because OTel's sender is expected to.** `RetryPolicy` is handed to the sender, not applied around it.
  [`RetrySchedule`](src/main/java/org/lolaf/staffix/monitoring/tracing/otlp/sender/RetrySchedule.java) reads the
  policy and applies **full jitter** — the delay is uniform in `[0, bound)` — because that is what OTel's own
  `RetryInterceptor` does, and dropping it would turn many exporters recovering against one collector into a
  synchronised stampede.
- **It marshals on the calling thread, into a pooled buffer held until the last attempt finishes.** The exporters are
  built with `MemoryMode.REUSABLE_DATA`, so the SDK reuses its marshalling buffers between batches and the bytes are
  only ours inside `writeMessage`. Marshalling and then handing the array to an executor would let the next batch
  overwrite a payload still in flight — and a retry holds it longer still. See
  [`PayloadBuffers`](src/main/java/org/lolaf/staffix/monitoring/tracing/otlp/sender/PayloadBuffers.java).
- **A non-2xx is reported as a response, not an error.** OpenTelemetry inspects the status and accounts for it itself;
  routing a 400 to the error callback would hide it from the exporter's own bookkeeping.

---

## Choosing the client

With nothing set, exports publish through [`jdk`](../../../http-clients/jdk) — no third-party dependency, matching what
the OTLP logs and metrics settings default to. To use another, name a class with a public no-argument constructor
implementing `Function<HttpSenderSettings, HttpSender>`, and put that client's module on the classpath:

```bash
-Dorg.lolaf.staffix.otlp.httpSenderFactory=com.example.OkHttpSenderFactory
```

A property rather than a setting, because `ServiceLoader` constructs the provider with no arguments — there is nowhere
to pass a factory in, which is why the logs and metrics sides can take one directly and this cannot.
`examples` has a worked example in `JettyOtlpHttpSenderFactory`.

### When the factory is a bean

A class name is all a property can express, so the factory it names has to be constructible from nothing — which rules
out one a dependency injection container built. An application that *can* build the factory hands it over instead,
through the second constructor, and reaches the exporter with OpenTelemetry's own `setComponentLoader`:

```java
StaffixHttpSenderProvider provider = new StaffixHttpSenderProvider(mySenderFactory);
builder.setComponentLoader(new ComponentLoader() {
    public <T> Iterable<T> load(Class<T> type) {
        return type == HttpSenderProvider.class
                ? List.of(type.cast(provider))
                : ServiceLoader.load(type);
    }
});
```

Under Spring Boot that belongs in the bean named by `http-span-exporter-customizer-bean`, which already receives the
builder — so no new property is needed, and the sender factory becomes an ordinary bean with whatever the context
injects into it, including an `HttpVersion`. A factory passed this way ignores the system property entirely; passing
`null` behaves exactly as the `ServiceLoader` path does. The same constructor exists on `StaffixGrpcSenderProvider`,
where it also replaces that provider's otherwise-mandatory property.

**The default is not the recommendation.** A production collector is HTTPS, and over TLS the JDK client allocates
4.3× what OkHttp does. See the [`http-clients` comparison](../../../http-clients/README.md) before deciding.

---

## Cost

The adapter's own bookkeeping allocates nothing per export once warm — the response object for a repeated status is
reused, the header array is rebuilt only when the headers change, and payload buffers come from a bounded pool. An
export as a whole is not free, though: the SDK marshals it and the client writes it.

Measured with `OtlpTracingExportBenchmark`, one export of a 512-span batch (the `maxExportBatchSize` `OtelTracing`
configures) over plaintext loopback to an out-of-process stub, JDK 21, `@Fork(2)`, 10 iterations. Allocation is
`gc.alloc.rate.norm`, bytes per export.

| arm | allocation | time |
|---|---|---|
| `STAFFIX_JETTY` | **38.5 KB** ±47 | 212 µs ±7 |
| `STAFFIX_OK_HTTP` | 41.1 KB ±44 | **200 µs** ±11 |
| `OTEL_OK_HTTP` — OpenTelemetry's own sender | 43.1 KB ±1,252 | 817 µs ±934 |
| `STAFFIX_JDK` — the default | 49.0 KB ±110 | 240 µs ±13 |

Two things in that table need saying plainly rather than being left to be noticed.

**OpenTelemetry's own OkHttp sender is several times slower here, and that is a Nagle stall, not a fair
loss.** 817 µs per export with a ±934 µs error bar — an error bar larger than the mean — is the signature of the
peer's 40 ms delayed-ACK timer firing on some exports and not others; an earlier run of the same benchmark put it at
22 ms ±14 ms. It is the same defect recorded as finding 1 of `otlp-http-clients.md`, where a stock `OkHttpClient`
writes request head and body as separate segments with Nagle enabled. Our OkHttp module sets `TCP_NODELAY` for itself,
which is why `STAFFIX_OK_HTTP` does not show it. Against a real collector over a real network the stall may not
reproduce; treat this as a reason to prefer a client that sets the option, not as a latency figure.

**The spread between the three `STAFFIX_*` arms is the client, not this module.** Subtract each client's own
per-publish cost, measured standalone in `OtlpHttpSenderBenchmark` with no adapter involved, and what is left is the
same for all three — about 33 KB of SDK marshalling for a 512-span batch:

| arm | total | client alone | remainder |
|---|---|---|---|
| `STAFFIX_JETTY` | 38.5 KB | ~5.7 KB | ~32.8 KB |
| `STAFFIX_OK_HTTP` | 41.1 KB | ~8.2 KB | ~32.9 KB |
| `STAFFIX_JDK` | 49.0 KB | ~16.8 KB | ~32.2 KB |

The adapter costs the same whichever client is under it, which is what it should do.

**What the client contributes is flat in the payload**, so the ordering above holds for a batch of any size. Measured
standalone, with no adapter in the picture:

| payload | `okhttp` | `jetty` | `jdk` |
|---|---|---|---|
| 16 KB | 8.2 KB | 5.7 KB | 16.8 KB |
| 64 KB | 8.3 KB | 5.8 KB | 16.4 KB |
| 128 KB | 8.2 KB | 5.7 KB | 16.9 KB |

**This is a recent correction, and it changed the recommendation.** OkHttp used to grow faster than the payload —
16.5 KB at 64 KB and 82.4 KB at 128 KB — which made `STAFFIX_OK_HTTP` the worst arm here at 57.6 KB and put it 13 KB
behind OpenTelemetry's own sender. The cause was `RequestBody.create(byte[], …)` handing okio the whole body in one
write; `SegmentedRequestBody` in `http-clients/okhttp` now writes it a segment at a time. Our OkHttp arm is
consequently 16.5 KB cheaper per export than it was, and now sits **ahead** of OpenTelemetry's sender rather than
behind it. There is no longer a large-export reason to prefer one of our clients over another.

| jars added | added weight |
|---|---|
| `opentelemetry-sdk-common` and one client module | whatever that client costs; `jdk` costs nothing |

Against the alternative — `opentelemetry-exporter-sender-okhttp` — this removes 2.9 MB, of which 1.7 MB is the Kotlin
runtime, from any application that traces and does not otherwise want OkHttp.

None of these figures should drive a decision on their own. A 512-span batch every five seconds is 8 KB/s of garbage
at the worst arm here; the dependency and the Nagle defect are the reasons that hold up.

---

## Two things to know before deploying it

**This module serves OTLP/HTTP only.** For `OtlpEndpointType.GRPC`, add
[`tracing-otlp-sender-grpc`](../tracing-otlp-sender-grpc) as well — a separate module because registering a
`GrpcSenderProvider` is classpath-global and should reach only the applications that ask for it. Selecting `GRPC` with
neither that module nor OTel's own sender on the classpath fails at startup with *"No GrpcSenderProvider found on
classpath"*. HTTP is the default endpoint type and needs nothing beyond this module.

**A retry policy can outlive the exporter timeout.** `RetryPolicy` defaults to 5 attempts with backoff bounds of 1,
1.5, 2.25 and 3.375 seconds — 8.125 s of waiting, plus the attempts themselves — while
`BatchSpanProcessor.setExporterTimeout` in `OtelTracing` gives a batch 10 s. `HttpSenderConfig` does not carry the
exporter timeout, so the sender cannot clamp itself to it. **Tune the two together**: raising the retry policy without
raising the exporter timeout means the SDK abandons batches the sender is still working on.

---

## When to choose it

- **Whenever you already use this library's HTTP clients for OTLP logs or metrics.** One client, one place a tuning is
  found, one dependency to reason about — which is the whole point.
- **When the Kotlin runtime on the classpath is a cost you would rather not pay** to publish telemetry.

Metrics are not part of this: micrometer's OTLP registry publishes through its own sender interface, which
`MicrometerHttpSenderAdapter` already bridges onto our clients, and it has no gRPC path at all.
- **If you export over OTLP/gRPC**, take [`tracing-otlp-sender-grpc`](../tracing-otlp-sender-grpc) alongside this
  module, and note that gRPC needs a client that can read HTTP/2 trailers — `okhttp` or `jetty`, never `jdk`.
