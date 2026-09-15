# `http-clients/jdk`

`HttpSender` on the JDK's own HTTP client. **The default, and not the recommendation.**

---

## What it is

[`JdkHttpSender`](src/main/java/org/lolaf/staffix/http/jdk/JdkHttpSender.java) publishes through
`java.net.http.HttpClient`, in the JDK since **Java 11**. No third-party dependency, which is the whole reason it is
what both the logs and the metrics settings default to: configure nothing, and this is what you get, without adding a
line to your pom.

It pins **HTTP/1.1** rather than taking the JDK's HTTP/2 default — against an HTTP/1.1 receiver, offering HTTP/2 costs
2.6 KB per publish in a refused `h2c` upgrade over cleartext, and 0.5 KB over TLS. That is the cost of the *offer
being declined*, which is all this module's `JDK_HTTP_CLIENT_H2` benchmark arm measures: it still answers from the
HTTP/1.1 receiver, unlike [`okhttp`](../okhttp)'s and [`jetty`](../jetty)'s `_H2` arms, which face a receiver that
speaks HTTP/2 and therefore price HTTP/2 actually being used (1.17–1.51× the allocation, 17–40% more latency). Note that pinning it also withdraws
the ALPN offer, so a collector that does speak HTTP/2 will not be taken up on it; the constructor takes a version if
that matters.

Two things it does that the client would not do on its own:

- **A zero-copy body publisher.** `BodyPublishers.ofByteArray` duplicates the payload into a fresh buffer on every
  subscribe — 44% of everything this client allocated on a plaintext publish, and measured at **16.4 KB per publish on
  both transports**. `ByteArrayRegionPublisher` hands out a `ByteBuffer.wrap` over the caller's own array instead,
  built per subscribe so a retried request sends the body from the start rather than from a buffer already drained.
- **The response body is discarded unread on success**, and buffered into a `String` only on the failing branch that
  reports it.

Every setting in `HttpSenderSettings` is honoured. Headers the client reserves for itself — which ones varies by JDK
version — are dropped with a warning logged once, rather than failing the publish.

---

## Cost

16 KB payload, receiver out-of-process over loopback, JDK 21, measured with `OtlpHttpSenderBenchmark`. Allocation is
`gc.alloc.rate.norm`, bytes per publish.

| | allocation | time |
|---|---|---|
| plaintext | 17 KB | 85.8 µs ±25.3 |
| TLS       | **56 KB** | 82.9 µs ±34.0 |

| jars added | added weight |
|---|---|
| none | 0 — `java.net.http` ships in the JDK |

**The TLS number is the point of this section.** 56 KB per publish is the worst of the three modules here — 4.2× what
[`okhttp`](../okhttp) spends over TLS and 2.0× what [`jetty`](../jetty) does. About three fifths of it is
`SSLFlowDelegate`, which allocates a fresh wrap and a fresh unwrap buffer per operation where a blocking `SSLSocket`
reuses its own. That is inside the JDK client; a caller cannot tune it, cannot pool around it, and cannot configure it
away.

It does now beat micrometer's `HttpUrlConnectionSender` on both transports, where before the zero-copy publisher it
lost to it over TLS. That makes it a defensible default, not a good HTTPS client.

**One thing this client is quietly good at, on plaintext: its allocation does not grow with the payload.** The
zero-copy publisher means a bigger export costs the same — this client holds ~16.8 KB from a 16 KB payload to a 128 KB
one. That is a genuine property, but it no longer buys a ranking: [`okhttp`](../okhttp) and [`jetty`](../jetty) are
flat on plaintext too, at 8.2 KB and 5.7 KB, so this stays the most expensive of the three at every size. (Until
OkHttp's request body was rewritten it grew to 82.4 KB at 128 KB and this client beat it outright above ~64 KB; that
is no longer the case.)

Over TLS that advantage is gone — `SSLFlowDelegate` allocates per operation, so more payload means more operations:
56.0 KB at 16 KB becomes 184.4 KB at 128 KB, still the worst of the three at both sizes.

The timings are the other half of the picture: this is the slowest of the three on both transports, and the only one
whose error bars run to tens of microseconds.

---

## It cannot serve gRPC, and no configuration changes that

gRPC reports the outcome of a call in HTTP/2 **trailers** — a failed call still answers HTTP 200 — and
`java.net.http.HttpResponse` has no accessor for trailers at all: `statusCode`, `request`, `previousResponse`,
`headers`, `body`, `sslSession`, `uri`, `version`, and nothing else. So this module implements
[`HttpSender`](../../api/src/main/java/org/lolaf/staffix/api/http/HttpSender.java) and not `GrpcSender`.

This is not a pinning question. The constructor already takes an `HttpVersion`, so HTTP/2 is one argument; the
trailers simply are not in the API. Use [`okhttp`](../okhttp) or [`jetty`](../jetty) for OTLP/gRPC.

It is also why the OTLP tracing gRPC provider has **no default client** where the HTTP one defaults to this module:
there is nothing safe to default to.

---

## When to choose it

- **When you want no dependencies.** That is the case it wins outright, and it is a real case.
- **When the collector is plaintext** and you would rather not add either of the others: 17 KB per publish is
  unremarkable at a 10 s step.
- **Not for an HTTPS collector**, which is what a production one is. Use [`okhttp`](../okhttp) there — 13.2 KB per
  publish against this client's 55.9 KB, and 39.7 µs against 82.9 µs, for 2.9 MB of dependency. At a 128 KB export the
  gap is wider still: 30.1 KB against 184.1 KB.
