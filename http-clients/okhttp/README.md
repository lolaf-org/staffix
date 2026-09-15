# `http-clients/okhttp`

`HttpSender` on OkHttp. **The one to use for an HTTPS collector**, if you can carry the dependency.

---

## What it is

[`OkHttpSender`](src/main/java/org/lolaf/staffix/http/okhttp/OkHttpSender.java) publishes through OkHttp 5, on
**Java 11**. It replaces two earlier senders that did the same job differently — micrometer's `OkHttpSender` on the
metrics side and a hand-written one on the logs side — and it fixes what both of them got wrong:

- **Nagle is disabled.** A stock `OkHttpClient` writes the request head and the request body as separate segments, and
  with Nagle on, the second waits for the peer to acknowledge the first. Against a receiver that delays
  acknowledgements — the Linux default — every publish stalls on that 40 ms timer: **42 ms per publish measured, against
  0.7 ms** with `TCP_NODELAY` set. `NoDelaySocketFactory` sets it, and it covers TLS too, since OkHttp builds the raw
  socket through the factory before wrapping it.
- **The content type goes on the wire exactly as configured.** Micrometer's own OkHttp sender appends
  `; charset=utf-8` to it, which some collectors reject on a binary payload — the examples had a branch working around
  precisely that.

Note it negotiates **HTTP/2 over TLS** by default, offering `[h2, http/1.1]`, where the other two modules pin HTTP/1.1.

Every setting in `HttpSenderSettings` is honoured; the request timeout becomes OkHttp's call timeout, covering the whole
exchange. A `java.net.Authenticator` answers both proxy (407) and server (401) challenges, `Basic` only — a
`PasswordAuthentication` is a username and a password, so a Digest or Negotiate challenge is declined rather than
answered wrongly. Static credentials belong in a bound `Authorization` header, which costs no rejected round trip at all.

---

## Cost

16 KB payload, receiver out-of-process over loopback, JDK 21, measured with `OtlpHttpSenderBenchmark`. Allocation is
`gc.alloc.rate.norm`, bytes per publish.

| | allocation | time |
|---|---|---|
| plaintext | 8.2 KB | **30.3 µs** |
| TLS       | **13.2 KB** | **39.7 µs** |

Best over TLS by a wide margin — the JDK client spends 56 KB there and Jetty 28 KB — and the fastest of the three on
both transports. On plaintext only Jetty allocates less, and by little.

It also allocates about 2.2 KB per publish less than micrometer's own OkHttp sender on both transports (10.3 KB and
15.5 KB), because the endpoint and the static headers bind once here rather than being reassembled per request.

### It holds at a larger payload

On plaintext, allocation here is flat in the payload, as it is for the other two:

| payload | `okhttp` | `jetty` | `jdk` |
|---|---|---|---|
| 16 KB | 8.2 KB | 5.7 KB | 16.8 KB |
| 64 KB | 8.3 KB | 5.8 KB | 16.4 KB |
| 128 KB | 8.2 KB | 5.7 KB | 16.9 KB |

Over TLS every client pays for the encryption buffers, but this one pays least and its margin **widens** with the
export rather than narrowing:

| payload | `okhttp` | `jetty` | `jdk` |
|---|---|---|---|
| 16 KB | **13.2 KB** | 27.9 KB | 55.9 KB |
| 64 KB | **20.5 KB** | 84.1 KB | 111.2 KB |
| 128 KB | **30.1 KB** | 159.0 KB | 184.1 KB |

4.2× cheaper than `jdk` at 16 KB and 6.1× at 128 KB; 2.1× cheaper than `jetty` at 16 KB and 5.3× at 128 KB.

**This was not always true, and the reason is worth recording.** Until `SegmentedRequestBody` was written, the body
went out through `RequestBody.create(byte[], …)`, which hands okio the whole payload in a single `write`: okio
materialises all of it as 8 KiB segments before one `emitCompleteSegments()` drains them. `SegmentPool` recycles at
most 64 KiB per thread-affine bucket, so every segment past the eighth was a fresh allocation and the cost grew with
the payload — **82.4 KB** per publish at 128 KB on plaintext, and **104.4 KB** over TLS. Writing a segment at a time
bounds the chain to two. The socket sees the same number of writes either way, because okio already drains the chain
segment by segment, so no throughput was traded for it.


| jars added | added weight | |
|---|---|---|
| 4 | **2.9 MB** | `okhttp-jvm` 843 KB, **`kotlin-stdlib` 1.7 MB**, `okio-jvm` 373 KB, `annotations` 17 KB |

**The Kotlin runtime is the cost that does not show up in a benchmark.** OkHttp 5 is written in Kotlin, so this module
puts a second language runtime — 58% of its footprint — onto the classpath of an application whose reason for existing
is a FIX session. In a tightly controlled deployment that is a real thing to weigh, and it is the reason this is a
module you opt into rather than something every consumer gets.

The artifact is `okhttp-jvm`, not `okhttp`: since OkHttp 5 the latter is a multiplatform stub carrying no classes.

---

## It also serves gRPC

This module implements [`GrpcSender`](../../api/src/main/java/org/lolaf/staffix/api/grpc/GrpcSender.java) as well, in
`OkHttpGrpcSender` — one of only two clients here that can. gRPC reports its outcome in HTTP/2 trailers, and OkHttp
exposes them through `Response.trailers()`; the [`jdk`](../jdk) client exposes none and so cannot serve gRPC at all.

It costs **no additional jars**: OkHttp already speaks HTTP/2, including the `H2_PRIOR_KNOWLEDGE` that cleartext gRPC
requires, since gRPC has no `h2c` upgrade handshake. `TCP_NODELAY` is set here too, for the same measured reason as on
the HTTP sender.

Both gRPC senders in this library are held to one contract, `AbstractGrpcSenderTest` in
[`grpc-test-kit`](../grpc-test-kit).

**It is the cheaper of the two on gRPC over TLS, and the more expensive on plaintext.** Measured by
`OtlpGrpcSenderBenchmark` on a 16 KB payload against one receiver: 28.8 KB per publish over TLS against
[`jetty`](../jetty)'s 35.5 KB, but 19.6 KB on plaintext against `jetty`'s 8.6 KB. The TLS margin is
`jetty`'s general TLS overhead rather than a gRPC effect — its HTTP TLS arm is already 26.8 KB against the
12.3 KB here. Against its own HTTP/1.1 path the same export costs 2.7× plaintext and 2.3× over TLS — gRPC
is a capability here, not an optimisation.

**Receiving a gRPC response is expensive here, and it is not the sender's doing.** Against this module's
*own* OTLP/HTTP over HTTP/2 — the same wire gRPC runs on — a gRPC publish costs 2.02× on plaintext and
1.52× over TLS, some 9.9 KB and 9.8 KB more. Bisecting it (`otlp-senders.md` §6) puts the whole of that on
the **receive** side: the identical request costs 10.6 KB when the receiver answers an empty 200 and
20.9 KB when it answers the framed message plus the trailing HEADERS frame gRPC carries its status in —
and it costs that whether the sender reads them or not, so no change to `OkHttpGrpcSender` avoids it.
[`jetty`](../jetty) receives the same three frames for 0.4 KB. It is a property of OkHttp's HTTP/2 receive
path, which is why the plaintext gRPC recommendation is `jetty`.

> Until 2026-09-07 this section said this module was the cheaper gRPC client "by a wide margin" at every
> combination. It was not: `jetty`'s figures were inflated by a double payload copy in its own sender, and
> both senders were charged 4.2 KB a call for a response body the HTTP senders never read. See
> `otlp-senders.md` §4.

---

## When to choose it

- **When the collector is HTTPS**, which a production one is. 13.2 KB per publish against 55.9 KB for
  [`jdk`](../jdk) is the single largest difference between any two clients here, and it is the fastest as well. The
  margin holds at every payload measured and grows with it: 6.1× cheaper than `jdk` and 5.3× cheaper than
  [`jetty`](../jetty) at 128 KB.
- **When you are already carrying OkHttp**, in which case the footprint argument disappears entirely.
- **For OTLP/HTTP over HTTP/2**, through `HttpVersion.HTTP_2` on the third constructor — 9.8 KB and
  41.0 µs per publish on plaintext, 18.5 KB and 59.0 µs over TLS. Ask for it when the collector wants it:
  against this sender's own HTTP/1.1 it is 1.17–1.39× the allocation and 1.32–1.40× the latency. Note
  that this sender used to negotiate HTTP/2 with any TLS collector that offered it, undeclared; it now
  pins HTTP/1.1 unless asked.
- **For large exports too.** Allocation is flat in the payload on plaintext and grows slowest of the three over TLS.
  `jetty` is still the cheaper of the two on plaintext, by about 2.5 KB per publish at any size.
- **Not when the classpath is tightly controlled** and 1.7 MB of Kotlin to publish telemetry is not a trade you want.
  [`jetty`](../jetty) is 1.9 MB with no second runtime, and better still on plaintext.
