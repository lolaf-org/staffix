# HTTP clients

One `HttpSender` abstraction, one module per HTTP client behind it.

Two places in this library push protobuf to an OTLP endpoint — the OTLP message logger and the OTLP meter registry —
and both do the same narrow thing: POST a byte array to a fixed endpoint with fixed headers, forever, once per flush
or step. That is what [`HttpSender`](../api/src/main/java/org/lolaf/staffix/api/http/HttpSender.java) is, and it is
deliberately no larger than that job: the endpoint, the content type and the headers that never change bind once, at
construction, which is what lets an implementation pool its connection and resolve its address a single time instead
of on every publish.

The implementations are one per client library, each in a module of its own, so an application depends on exactly the
client it picked and pays for nothing else. **Which one to pick is a real decision** — they differ by a factor of five
in allocation and by 2.9 MB in dependency weight, and the cheapest to run is the heaviest to carry.

---

## The choice

| module                | allocation, plaintext | allocation, TLS | µs, plaintext | µs, TLS  | jars added | weight  | Java |
|-----------------------|-----------------------|-----------------|---------------|----------|------------|---------|------|
| [`jdk`](jdk)          | 17 KB                 | 56 KB           | 85.8          | 82.9     | none       | 0       | 11   |
| [`okhttp`](okhttp)    | 8.2 KB                | **13.2 KB**     | **30.3**      | **39.7** | 4          | 2.9 MB  | 11   |
| [`jetty`](jetty)      | **5.9 KB**            | 28 KB           | 33.1          | 64.1     | 12         | 2.2 MB  | 17   |
| micrometer's default¹ | 59 KB                 | 64 KB           | 36.0          | 53.8     | none       | 0       | 8    |
| micrometer's OkHttp¹  | 10 KB                 | 15 KB           | 38.7          | 54.8     | 4          | 2.9 MB  | 8    |

¹ `HttpUrlConnectionSender` and `OkHttpSender`, micrometer's own. Not modules here; listed because they are what the
metrics side published through before, and so are the baseline the rest are worth measuring against. The OkHttp one is
measured with `TCP_NODELAY` set, which it does not do for itself — see [the OkHttp module](okhttp) for what leaving it
alone costs.

The `jdk` timings carry error bars far wider than the others — ±25 µs plaintext and ±34 µs TLS, against ±1.1 µs for
`okhttp` and `jetty` on plaintext and ±7–11 µs over TLS; read them as "slower, and erratic" rather than as a figure.

### Every row above is one 16 KB export, and they generalise

Allocation was re-measured across payload sizes. On plaintext all three clients are flat — the same allocation
whatever the payload:

| payload | `jetty` | `okhttp` | `jdk` |
|---|---|---|---|
| 16 KB | **5.7 KB** | 8.2 KB | 16.8 KB |
| 64 KB | **5.8 KB** | 8.3 KB | 16.4 KB |
| 128 KB | **5.7 KB** | 8.2 KB | 16.9 KB |

Over TLS every client pays for the encryption buffers and none of them is flat, but they diverge sharply — `okhttp`
grows slowest and its margin widens with the export:

| payload | `okhttp` | `jetty` | `jdk` |
|---|---|---|---|
| 16 KB | **13.2 KB** | 27.9 KB | 55.9 KB |
| 64 KB | **20.5 KB** | 84.1 KB | 111.2 KB |
| 128 KB | **30.1 KB** | 159.0 KB | 184.1 KB |

So the ordering in the table above holds at every size measured: **`jetty` cheapest on plaintext, `okhttp` cheapest
over TLS**, and the TLS gap grows from 4.2× to 6.1× against `jdk` as the export grows.

**This was not always true of `okhttp`.** Until its request body was rewritten it allocated 16.5 KB at 64 KB and
82.4 KB at 128 KB on plaintext — the worst of the three past about 64 KB — because okio was handed the whole payload
in one write and had to materialise it as segments before flushing any. See [the OkHttp module](okhttp) for the
mechanism; the numbers above are the ones that hold now.

**Read it this way:**

- **`jdk` is the default** on both settings, and the default is not the recommendation. It is the default for one
  reason only: it needs nothing on the classpath, so a consumer who configures nothing still gets a working sender.
- **A production collector is HTTPS**, and over TLS `jdk` is the worst of the three. About three fifths of what it
  spends there is `SSLFlowDelegate` allocating fresh wrap and unwrap buffers per operation, which no caller can tune.
  **Point an HTTPS collector at [`okhttp`](okhttp)**, which allocates 4.2× less at 16 KB — and 6.1× less at 128 KB,
  the margin widening as the export grows — and is nearly twice as fast.
- **`jetty` is the cheapest on plaintext**, by about 2.5 KB per publish at any payload size. It needs Java 17, and
  over TLS it allocates 2.1× what `okhttp` does at 16 KB and 5.3× at 128 KB. It is the pick for an in-cluster sidecar
  reached over cleartext. It is not the pick for TLS at any size, and least of all for a large export.
- **The best client on the benchmark carries the heaviest dependency.** OkHttp 5 is a Kotlin library, so choosing it
  puts `kotlin-stdlib` — 1.7 MB, 58% of its footprint — onto the classpath of a FIX engine, to publish telemetry. That
  cost is invisible in the allocation column and it is a fair reason to choose otherwise.

At a 10 s step the spread between best and worst here — 43 KB per publish, `jdk` against `okhttp` over TLS — is about
4 KB/s of garbage. The allocation numbers are not, on their own, why these modules exist; the duplication they removed
and the two defects they fixed are (see [the OkHttp module](okhttp) for both).

### How it was measured

`OtlpHttpSenderBenchmark` in `benchmarks` (JMH, `@Fork(2)`, 16 KB payload, receiver in a JVM of its own
over loopback, JDK 21). Allocation is `gc.alloc.rate.norm` — bytes per publish, and the number that matters; time is
average per publish.

```bash
mvn clean install -pl benchmarks -am
java -jar benchmarks/target/benchmarks.jar OtlpHttpSenderBenchmark -prof gc
```

These are the ported implementations, measured through the same adapter the metrics side publishes through — not the
clients they were ported from. Two differences from that earlier measurement are worth knowing, because they change
the advice: the `jdk` module's zero-copy body publisher took 16.4 KB per publish off **both** transports, which is why
it is no longer worse than micrometer's default over TLS; and `http-clients/okhttp` allocates about 2.2 KB less per
publish than micrometer's own OkHttp sender, because its headers bind once rather than per request.

---

## Using one

```java
HttpSenderSettings settings = HttpSenderSettings.builder()
        .endpointUrl("https://collector.example.com/v1/metrics")
        .header("Authorization", "Basic …")
        .connectTimeout(Duration.ofSeconds(1))
        .requestTimeout(Duration.ofSeconds(10))
        .build();

try (HttpSender sender = new OkHttpSender(settings)) {
    sender.send(payload, payload.length);
}
```

`send` returns the HTTP status of a successful exchange and throws `HttpResponseException`, carrying the status and
the response body, on anything else. A response body is only ever read on that failing branch, which is what keeps a
successful publish cheap.

Settings each module cannot honour exactly are documented on its implementation class rather than silently dropped —
Jetty's proxy and authenticator handling is the one place where the models genuinely differ.

---

## The modules

| module                | |
|-----------------------|--|
| [`http-test-kit`](http-test-kit)| `AbstractHttpSenderTest`, the contract every implementation has to meet, plus the stub HTTP server it runs against. Published as a test-jar; a client module's test class extends it, implements `createSender`, and adds only what is peculiar to that client |
| [`grpc-test-kit`](grpc-test-kit)| `AbstractGrpcSenderTest` and a stub gRPC endpoint on HTTP/2. A second kit rather than more classes in the first, because its stub needs a server that can send trailers and `jdk` can never use it |
| [`jdk`](jdk)          | `java.net.http.HttpClient`. No dependency. **Cannot** implement `GrpcSender`: its `HttpResponse` exposes no HTTP/2 trailers, and that is where gRPC puts its status |
| [`okhttp`](okhttp)    | OkHttp. Best over TLS. Also implements `GrpcSender` |
| [`jetty`](jetty)      | Jetty's `HttpClient`. Best over plaintext, needs Java 17. Also implements `GrpcSender` |

The contract lives in a kit of its own on purpose: a difference in behaviour between two of these — a per-request
header that replaces a bound one on two clients and is appended by the third, say — is a bug, and the only way that
surfaces is by asserting the same things against all of them. That has now caught two: the appended header, and a gRPC
method path whose leading slash one caller supplies and another does not.

## Two protocols, and only one of them is served by all three

`HttpSender` is implemented by every client here. **`GrpcSender` is implemented by `okhttp` and `jetty` only**, because
gRPC reports its outcome in HTTP/2 trailers and `java.net.http.HttpResponse` exposes none — see [`jdk`](jdk) for why no
configuration changes that. Each interface has its own contract test, and a client module extends the ones it can meet.

Where the two protocols reach, across this library's three OTLP paths:

| path | HTTP | gRPC |
|---|---|---|
| logs (`messages-loggers/otlp`) | yes | yes, via a `transport` setting |
| traces (`monitoring/tracing-otlp`) | yes | yes, via `tracing-otlp-sender-grpc` |
| metrics (`monitoring/micrometer-otlp`) | yes | **no** — micrometer's OTLP registry has no gRPC sender to plug into |

### What gRPC costs

`OtlpGrpcSenderBenchmark` publishes the same 16 KB payload over three arms on the same client against one
receiver, so the receiver is not what the comparison measures. The third arm matters: gRPC is HTTP/2 and
OTLP/HTTP conventionally is not, so comparing only those two charges gRPC for everything HTTP/2 costs.
Allocation per publish, `gc.alloc.rate.norm`:

| client | transport | HTTP/1.1 | HTTP/2 | gRPC | h2 / h1 | **gRPC / h2** |
|---|---|---|---|---|---|---|
| `okhttp` | plaintext | 7.3 KB | 9.7 KB | 19.6 KB | 1.32× | **2.02×** |
| `okhttp` | TLS | 12.3 KB | 19.0 KB | 28.8 KB | 1.55× | **1.52×** |
| `jetty` | plaintext | 4.8 KB | 8.2 KB | 8.6 KB | 1.72× | **1.04×** |
| `jetty` | TLS | 26.8 KB | 33.1 KB | 35.5 KB | 1.23× | **1.07×** |

**Most of what looks like the cost of gRPC is the cost of HTTP/2.** On `jetty`, gRPC over HTTP/2 costs
1.04–1.07× the same client's plain OTLP/HTTP over HTTP/2 — the framing and trailers are very nearly free,
and the whole difference is the version underneath. On `okhttp` a gRPC publish costs 1.5–2.0× above its own
HTTP/2 arm, and bisection (`otlp-senders.md` §6) puts all of it on the **receive** side: OkHttp allocates
about 10 KB more to take in the framed message and the trailing HEADERS frame than to take in an empty
200, whether the sender reads them or not. That is OkHttp's HTTP/2 receive path, not our sender and not
the protocol — `jetty` receives the same frames for 0.4 KB.

Against ordinary HTTP/1.1 OTLP the end-to-end premium is 1.3× to 2.7×. Choose gRPC when it is the ingress
on offer, not to go faster.

### What HTTP/2 costs on plain OTLP/HTTP

Every sender here defaults to `HttpVersion.HTTP_1_1` and takes `HTTP_2` as a constructor argument. The
default is not an accident. Measured by `OtlpHttpSenderBenchmark` against its raw-socket receiver, which
speaks both versions, on a 16 KB payload:

| client | transport | alloc h1 → h2 | | latency h1 → h2 | |
|---|---|---|---|---|---|
| `okhttp` | plaintext | 8.4 KB → 9.8 KB | 1.17× | 31.2 µs → 41.0 µs | 1.32× |
| `okhttp` | TLS | 13.3 KB → 18.5 KB | 1.39× | 42.1 µs → 59.0 µs | 1.40× |
| `jetty` | plaintext | 5.8 KB → 8.8 KB | 1.51× | 32.0 µs → 40.9 µs | 1.28× |
| `jetty` | TLS | 27.9 KB → 33.0 KB | 1.18× | 52.0 µs → 60.7 µs | 1.17× |

**HTTP/2 costs 17–40% more latency and 1.17–1.51× the allocation**, on both clients and both transports.
An OTLP export is one small POST answered by an empty body, so there is nothing for multiplexing to buy.
Ask for `HTTP_2` when the collector wants it, not to go faster.

Two cautions on `HTTP_2`: on cleartext it is **prior knowledge**, so it fails against an HTTP/1.1
collector rather than degrading to one, while over TLS it is an ALPN preference with HTTP/1.1 behind it.
And [`jdk`](jdk)'s `JDK_HTTP_CLIENT_H2` benchmark arm measures something else entirely — a *refused*
negotiation against an HTTP/1.1 receiver — so do not read it as an HTTP/2 figure.

> An earlier version of this table reported 2.5×–8.5× and named `okhttp` the gRPC client at every
> combination. Both were artefacts of two defects in our own senders — `JettyGrpcSender` copied the
> payload twice per publish, and both gRPC senders drained the response body through a 4 KB throwaway
> buffer where the HTTP senders leave it unread. `otlp-senders.md` §4 records the diagnosis; the numbers
> above are from after the fix, with the HTTP/2 arm added to separate the two costs.

**That benchmark reports allocation only.** Its receiver is MockWebServer, which speaks the HTTP/2 and
trailers a raw socket cannot, and costs roughly 170 µs per exchange doing it — enough to dominate the
timings and swamp any protocol difference. Allocation is unaffected, because `gc.alloc.rate.norm` counts
only the publishing JVM. For client latency use `OtlpHttpSenderBenchmark`, which answers from a raw
socket; there is no honest gRPC counterpart today.
