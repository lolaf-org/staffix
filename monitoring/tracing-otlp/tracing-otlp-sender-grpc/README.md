# `tracing-otlp-sender-grpc`

Adds OTLP/**gRPC** to [`tracing-otlp-sender`](../tracing-otlp-sender): one class and one services file.

---

## What it is

OpenTelemetry resolves its gRPC transport through the `io.opentelemetry.sdk.common.export.GrpcSenderProvider` SPI,
the same way it resolves the HTTP one. This module registers
[`StaffixGrpcSenderProvider`](src/main/java/org/lolaf/staffix/monitoring/tracing/otlp/sender/grpc/StaffixGrpcSenderProvider.java)
against it, so `OtlpGrpcSpanExporter` calls through this library's `GrpcSender` instead of a client of its own.

**Why it is a module and not two more files next door.** A `META-INF/services` file is classpath-global. Registering
the gRPC provider beside the HTTP one would ship it to everyone who wanted only OTLP/HTTP — including anyone who kept
OpenTelemetry's `sender-okhttp`. Two providers would then be registered, OpenTelemetry would take whichever it found
first, and if that were ours it would demand a mandatory property for a feature the consumer never asked for.
Classpath order deciding whether an application starts is not a defensible design, so the registration lives here and
reaches exactly the people who added this dependency.

---

## Choosing the client, which you must

```bash
-Dorg.lolaf.staffix.otlp.grpcSenderFactory=com.example.OkHttpGrpcSenderFactory
```

The property names a class with a public no-argument constructor implementing
`Function<GrpcSenderSettings, GrpcSender>`, and its module must be on the classpath.

**Unlike the HTTP side, there is no default**, and that is not an omission. gRPC reports its outcome in HTTP/2
trailers — a failed call still answers HTTP 200 — and `java.net.http.HttpResponse` exposes no trailers at all. So the
`jdk` client, which is what the HTTP provider defaults to precisely because it needs no dependency, cannot serve gRPC
under any configuration. Only [`okhttp`](../../../http-clients/okhttp) and [`jetty`](../../../http-clients/jetty) can,
and one of them has to be named.

Unset, this refuses to build a sender with a message naming the property and both modules that satisfy it. That
refusal surfaces while the exporter is being built — inside `OtelTracing.startMe()` — so a misconfiguration stops the
engine starting instead of quietly dropping every span.

### When the factory is a bean

`StaffixGrpcSenderProvider` has a second constructor taking the factory directly, for an application that builds one
rather than naming it — and that **replaces the property**, mandatory though it otherwise is. The instance reaches the
exporter through OpenTelemetry's `setComponentLoader`, which under Spring Boot belongs in the bean named by
`grpc-span-exporter-customizer-bean`. See [`tracing-otlp-sender`](../tracing-otlp-sender/README.md#when-the-factory-is-a-bean)
for the shape; it is the same on both providers. Passing `null` behaves as the `ServiceLoader` path does, refusal
included.

---

## Cost

| jars added | added weight |
|---|---|
| this module, and one gRPC-capable client module | see [`okhttp`](../../../http-clients/okhttp) (2.9 MB) or [`jetty`](../../../http-clients/jetty) (2.2 MB) |

Nothing per export that [`tracing-otlp-sender`](../tracing-otlp-sender) does not already cost: the payload buffers,
the retry schedule and the cached response object are shared with the HTTP path, and the gRPC frame is a five-byte
header.

---

## What it does not cover

- **Metrics.** micrometer's OTLP registry has no gRPC sender abstraction to plug into, so OTLP metrics are HTTP-only
  whatever is on the classpath.
- **Logs.** They do not go through OpenTelemetry's exporters at all — `OtlpMessagesLogger` owns its own path and takes
  a `transport` setting plus a `grpcSenderFactory` directly.

So this module is about **traces**, and it is one of the two OTLP paths that gRPC reaches.
