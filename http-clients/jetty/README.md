# `http-clients/jetty`

`HttpSender` on Jetty's HTTP client. **The cheapest and fastest over plaintext. Requires Java 17.**

---

## What it is

[`JettyHttpSender`](src/main/java/org/lolaf/staffix/http/jetty/JettyHttpSender.java) publishes through Jetty 12's
`HttpClient`. Jetty pools the byte buffers it does I/O and TLS through, where the JDK client allocates a fresh pair per
TLS operation and the legacy `HttpURLConnection` stack copies the entity through a stream — buffer reuse is exactly what
a publisher sending one sizable POST per step forever wants.

The payload goes out as a `ByteBufferRequestContent` over `ByteBuffer.wrap`, so sending a slice of a caller's reusable
buffer costs no copy.

**Java 17 is not negotiable here.** Jetty 12 is compiled for it, while the rest of this library targets Java 11, which
is why this client has a module of its own with a raised `maven.compiler.release` rather than an optional dependency
inside a Java 11 module — the latter builds cleanly and then fails to link at runtime, which is the worst of both.

Three settings Jetty honours differently from the other two modules, because its model differs:

- **Proxy** — only an HTTP proxy is taken from the `ProxySelector`, resolved once for the bound endpoint since the
  endpoint never changes. A SOCKS proxy is reported and ignored.
- **Authenticator** — consulted once at construction rather than per challenge, because Jetty's authentication store is
  populated up front. `Basic` only.
- **Response body** — buffered by Jetty's blocking API whether or not it is wanted, though a `String` is built from it
  only on the failing branch. That is deliberate: abandoning an unread body aborts the exchange and drops the pooled
  connection, which costs far more per publish than buffering the empty body an OTLP receiver answers with.

---

## Cost

16 KB payload, receiver out-of-process over loopback, JDK 21, measured with `OtlpHttpSenderBenchmark`. Allocation is
`gc.alloc.rate.norm`, bytes per publish.

| | allocation | time |
|---|---|---|
| plaintext | **5.7 KB** | 33.1 µs |
| TLS       | 27.9 KB | 64.1 µs |

The cheapest of them all on plaintext — a third of what the JDK client allocates there — with
[`okhttp`](../okhttp) matching it on time. Over TLS it is second: ahead of `jdk`'s 56 KB and micrometer's 64 KB, 2.1×
behind `okhttp`'s 13.2 KB, and the slowest of the three in time.

**On plaintext, this number does not move with the payload** — and neither do the other two clients':

| payload | `jetty` | `okhttp` | `jdk` |
|---|---|---|---|
| 16 KB | **5.7 KB** | 8.2 KB | 16.8 KB |
| 64 KB | **5.8 KB** | 8.3 KB | 16.4 KB |
| 128 KB | **5.7 KB** | 8.2 KB | 16.9 KB |

This client stays ahead by about 2.5 KB per publish at every size, so on plaintext it is the cheapest whatever the
export looks like — but by a steady margin, not a widening one. (It once won here by more than an order of magnitude
at 128 KB; that was OkHttp allocating 82.4 KB through a request body since rewritten, not this client improving.)

**None of that carries over to TLS.** Once encryption is in the path every client scales with the payload, this one
steeply: 27.9 KB at 16 KB becomes 84.1 KB at 64 KB and 159.0 KB at 128 KB, while [`okhttp`](../okhttp) grows far more
slowly (13.2 KB, 20.5 KB, 30.1 KB) and ends up **5.3× cheaper** at 128 KB. Over TLS the gap against OkHttp widens with
the export rather than narrowing.

| jars added | added weight | |
|---|---|---|
| 12 | 2.2 MB | `jetty-util` 714 KB, `jetty-http` 468 KB, `jetty-io` 377 KB, `jetty-client` 363 KB, `jetty-http2-common` 219 KB, `jetty-http2-client-transport` 44 KB, `jetty-http2-hpack` 38 KB, `jetty-http2-client` 17 KB, plus alpn and compression; `slf4j-api` is already on the classpath |

**Four of those twelve are the HTTP/2 stack, and only `JettyGrpcSender` uses them.** gRPC is HTTP/2 or
nothing - there is no `h2c` upgrade to fall back on - so the gRPC sender is pinned to Jetty's HTTP/2
transport, which lives outside `jetty-client`. They cost 318 KB, and a consumer using only
`JettyHttpSender` still carries them: splitting a fourth module to spare two thirds of a megabyte was not
judged worth it. Even so this remains lighter than [`okhttp`](../okhttp)'s 2.9 MB, and with no second
language runtime in it.

Lighter than OkHttp's 2.9 MB, and with no second language runtime in it.

---

## It also serves gRPC

This module implements [`GrpcSender`](../../api/src/main/java/org/lolaf/staffix/api/grpc/GrpcSender.java) as well, in
`JettyGrpcSender` — one of only two clients here that can, since gRPC puts its status in HTTP/2 trailers and Jetty
exposes them through `Response.getTrailers()`. The [`jdk`](../jdk) client exposes none and never will.

Unlike `JettyHttpSender`, which negotiates its protocol, the gRPC sender is pinned to Jetty's HTTP/2 transport: gRPC
is HTTP/2 or nothing, and on cleartext there is no `h2c` upgrade to fall back to. That transport is why four of this
module's twelve jars exist — see the footprint table above, which counts them even for a consumer who only uses
`JettyHttpSender`.

Both gRPC senders in this library are held to one contract, `AbstractGrpcSenderTest` in
[`grpc-test-kit`](../grpc-test-kit).

**On plaintext gRPC this is the cheaper of the two clients, and its HTTP plaintext advantage carries
straight over.** Measured by `OtlpGrpcSenderBenchmark` on a 16 KB payload against one receiver: 8.6 KB per
publish on plaintext against [`okhttp`](../okhttp)'s 19.6 KB, which is 2.3× less. Over TLS the order
reverses — 35.5 KB against 28.8 KB, 1.2× more — but that is this module's general TLS overhead rather than
anything about gRPC, since its *HTTP* TLS arm is already 26.8 KB against `okhttp`'s 12.3 KB.

**gRPC is very nearly free here once HTTP/2 is paid for.** Against this module's *own* OTLP/HTTP over
HTTP/2 — the same wire gRPC runs on — the framing and trailers add 1.04× on plaintext and 1.07× over TLS,
0.4 KB and 2.4 KB per publish. The apparent gRPC premium against HTTP/1.1, 1.8× and 1.3×, is almost
entirely the HTTP/2 underneath rather than gRPC. `okhttp` cannot say the same: a gRPC publish costs
1.5–2.0× above its own HTTP/2 arm, all of it on the receive side — it allocates about 10 KB more than this
module does to take in the very same response frames.

> Until 2026-09-07 this section said the opposite — 42.2 KB on plaintext, 8.5× its own HTTP path, and
> "if gRPC is the protocol, `okhttp` is the client". That was `JettyGrpcSender` copying the payload twice
> on every publish, 32 KB of garbage on a 16 KB export to prepend a five-byte header, which the benchmark
> then reported as the cost of gRPC. See `otlp-senders.md` §4.

---

## When to choose it

- **For a plaintext collector on Java 17+** — an in-cluster sidecar, which is the common shape. Nothing else here
  allocates less on that path.
- **When you want the lightest of the two third-party clients** and TLS is not in play.
- **For plaintext gRPC**, where it allocates 2.3× less than [`okhttp`](../okhttp) — provided the four
  extra jars the HTTP/2 transport needs are an acceptable trade. Its gRPC framing costs 1.04× its own
  HTTP/2 arm, so on this client the protocol choice is very nearly free once HTTP/2 is.
- **For OTLP/HTTP over HTTP/2**, which `JettyHttpSender` now serves through `HttpVersion.HTTP_2` on its
  third constructor, reusing the HTTP/2 transport this module already carries — no new jars. It stays the
  cheapest client on plaintext there too, 8.8 KB against [`okhttp`](../okhttp)'s 9.8 KB, though the
  1.51× it pays over its own HTTP/1.1 is the largest jump of any arm measured — its HTTP/1.1 plaintext
  figure is simply very low.
- **Not for gRPC over TLS**, where [`okhttp`](../okhttp) allocates 1.2× less and needs no extra jars.
- **Not on Java 11 to 16**: the module does not build there, let alone run.
- **Not as a default**, for the same reason — while the library targets Java 11, a default that needs 17 is not one.
- **Not for an HTTPS collector**: [`okhttp`](../okhttp) allocates 2.1× less there at 16 KB, 5.3× less at
  128 KB, and is a third faster.
