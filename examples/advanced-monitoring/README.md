# `advanced-monitoring` — metrics, logs and traces over OTLP

A quote request/response session running under full observability: every FIX message is exported as a log line, the
session's latencies and throughput as metrics, and the message handling as spans — all over OTLP, to either a local
Grafana stack or Grafana Cloud.

It is the same FIX workload as [`QuoteRequestExample`](../staffix-api-examples); what this example adds is everything
around it. Read `QuoteRequestExample` first if you want the FIX part.

## What it exports

| signal  | produced by                                                                                                                     | how it is enabled | lands in   |
|---------|---------------------------------------------------------------------------------------------------------------------------------|-------------------|------------|
| metrics | [`monitoring/micrometer-otlp`](../../monitoring/micrometer-otlp) — a Micrometer `PushMeterRegistry` stepping every 1s           | `-em`             | Prometheus |
| logs    | [`messages-loggers/otlp`](../../messages-loggers/otlp) — every inbound and outbound FIX message                                 | `-lt=OTLP`        | Loki       |
| traces  | [`monitoring/tracing-otlp`](../../monitoring/tracing-otlp) — `OtelTracing`, propagating W3C trace context between the two sides | `-et`             | Tempo      |

All three are session plugins, so nothing on the message path knows they exist. The resource attributes they tag their
data with are `service.name=MetricsLogsTracesExample` and `service.instance.id=ACCEPTOR` or `INITIATOR` — that is how
you tell the two sides apart in Grafana.

## Running against the local stack

The [`monitoring/grafana`](../../monitoring/grafana) folder holds a docker compose stack — Grafana, Prometheus, Loki,
Tempo and an OpenTelemetry collector — already wired for this example. The collector listens for OTLP/HTTP on
`localhost:4318` and fans the three signals out to the three backends.

Start the stack, then build and run the example — the first two commands from the repository root:

```bash
mvn package -pl examples/advanced-monitoring -am
```

```bash
(cd monitoring/grafana && docker compose up -d)
cd examples/advanced-monitoring
./MetricsLogsTracesExample.sh -d=660 -w=1000 -jw -em -am -et -lt=OTLP -oe="http://localhost:4318"
```

The [launcher script](MetricsLogsTracesExample.sh) is the way to run this one: it passes the JVM flags listed in the
[examples README](../README.md#jvm-flags), which the latency numbers on the dashboard depend on.

Then open **<http://localhost:3000>**. Anonymous access is enabled with the Admin role and the login form is turned off,
so there is nothing to log in with, and the *Staffix FIX monitoring* dashboard is the home dashboard — it is what you
land on.

The example prints its full configuration on startup and then waits for a keypress before starting, so you have time to
get the dashboard open first.

Live stack logs are available with `docker compose logs -f`, and `docker compose down` stops everything.

## Running against Grafana Cloud

Copy `.env.example` to `.env` and fill in your endpoint and token:

```bash
source .env
./MetricsLogsTracesExample.sh -d=360 -w=70000 -em -et -lt=OTLP -oe=$OTLP_ENDPOINT -oa=$GRAFANA_CLOUD_TOKEN
```

`-oa` is sent verbatim as `Authorization: Basic <value>`, so it must be your base64-encoded
`instanceID:apiToken`, not the raw API token. No endpoint-specific handling is needed: the OTLP exporters
publish through `staffix-http-client-jetty`, which sends the content type through unchanged. Grafana Cloud
is an HTTPS collector, though, and `staffix-http-client-okhttp` allocates 2.1x less over TLS — swap the
dependency in `examples-core/pom.xml` and the two `httpSenderFactory` calls in `FixExamplesBase` if you
run this against a remote collector for long.

The token never appears in the configuration banner the example prints — options whose name looks like a credential are
masked.

## Reading the dashboard

Three variables scope every panel: **group** (`fix_sgid`, i.e. `acceptor` or `initiators`), **sid** (the FIX session id)
and **serviceName** (the Loki service, used by the log panel). The dashboard auto-refreshes every 10s over a 15-minute
window.

| panel                                      | what it tells you                                         |
|--------------------------------------------|-----------------------------------------------------------|
| Sessions                                   | logon state per session in the selected group             |
| FIX logs                                   | the raw FIX messages, both directions, streamed from Loki |
| Read / Write Latency Heatmap               | full latency distribution — where the tail actually is    |
| Read / Write Latency by Session & Msg Type | p50 / p95 / p99, split by message type                    |
| Read / Write Throughput                    | messages per second by direction and type                 |
| Network round trip time                    | measured by the session's own RTT probes, every second    |
| Clock offset                               | estimated offset between the two peers' clocks            |

Traces are not on this dashboard — explore them from Grafana's Tempo datasource (Tempo has no host port of its own, it
is only reachable inside the compose network). You will find `fix-msg-decoding` spans from both sides and a
`quote-response` span on the acceptor, each tagged with the `service.instance.id` that produced it.

## Options that matter here

The example takes all the [shared example options](../README.md#the-rest); these are the ones this example exists to
demonstrate. `--help` lists the rest.

| option        | effect                                                                                 |
|---------------|----------------------------------------------------------------------------------------|
| `-em`         | export metrics                                                                         |
| `-et`         | export traces                                                                          |
| `-lt=OTLP`    | export FIX messages as OTLP logs (rather than to file, the default)                    |
| `-oe`         | OTLP endpoint, default `http://localhost:4318`                                         |
| `-oa`         | OTLP `Authorization: Basic` value — needed for Grafana Cloud, not for the local stack  |
| `-am`         | run the monitoring callbacks asynchronously, off the message path                      |
| `-tc` / `-tw` | cap monitoring callbacks to `-tc` per `-tw` ms, dropping the overflow on the hot path  |
| `-otr`        | OTLP transport for logs and traces: `HTTP` (default) or `GRPC`                         |
| `-ohv`        | HTTP version the OTLP HTTP senders speak: `HTTP_1_1` (default) or `HTTP_2`             |
| `-jw`         | run a 2-minute JVM warmup before starting, so the measured run is already JIT-compiled |
| `-w`          | delay in micros before answering a quote request, i.e. how hard to drive the session   |

`-am` and `-tc`/`-tw` are the two knobs that keep observability off the latency-critical path: `-am` defers the plugin's
fire-and-forget callbacks to a shared consumer pool, `-tc` sheds them entirely above a rate. Both are worth turning on
before drawing conclusions from a latency measurement.

## Seeing what the JVM warmup buys you

The `-jw` flag in the command lines above runs a [JVM warmup](../../jvm-warmup) before the example's own sessions
start: a loopback FIX session driving the whole message path until C2 has compiled it, so the real sessions begin
their work on hot code rather than interpreted code.

This dashboard is the easiest place to see what that is worth. Run the example twice, once each way, and compare the
first minute of the read/write latency heatmaps:

```bash
./MetricsLogsTracesExample.sh -d=300 -w=1000 -em -am -et -lt=OTLP -oe="http://localhost:4318"      # cold
./MetricsLogsTracesExample.sh -d=300 -w=1000 -jw -em -am -et -lt=OTLP -oe="http://localhost:4318"  # warmed
```

Without `-jw` the first messages after logon land in the slow buckets of the heatmap and the p99 line starts high
and decays as the JIT catches up. With `-jw` the session opens flat — the warmup absorbed the compilation, two
minutes earlier, on traffic nobody was waiting for. That is the whole argument for warming up before an open,
made visible in one panel.

Watch the log during startup: the warmup announces itself, reports what it exchanged when it finishes, and only
then do the example's own sessions log on.

## What this example configures beyond the others

It overrides `getFixSessionSetting` to add three things the other examples do not need:

- **`allowUserDefinedFields`** — W3C trace context travels in a user-defined field (tag ≥ 5000), so tracing does not
  work without it.
- **`sendingTimeAccuracy(NANOSECONDS)`** — `SendingTime(52)` at nanosecond resolution, so the latency metrics are not
  quantised by the timestamp itself.
- **`rttMeasurementSettings(probeInterval = 1s)`** — what feeds the round-trip-time and clock-offset panels.

## Exporting over OTLP/gRPC instead of HTTP

These examples publish over **OTLP/HTTP** by default, which is the common path and the one every client module can
serve. Two of the three paths can use gRPC instead if your collector only offers it, and this example takes
**`-otr GRPC`** to switch both at once:

```bash
./run.sh -em -et -lt=OTLP -otr GRPC -oe http://localhost:4317
```

That sets the logs transport, selects `OtlpEndpointType.GRPC` for tracing, and names `JettyOtlpGrpcSenderFactory` on
the system property the tracing SPI reads — the three things the sections below describe doing by hand. Mind the
port: gRPC collectors listen on **4317**, not 4318.

gRPC is not the faster choice. It allocates 1.3–2.7× what OTLP/HTTP does per publish, most of that being the HTTP/2
it runs on rather than the framing; see [`http-clients`](../../http-clients/README.md#what-grpc-costs). Pick it when
the collector offers no HTTP ingress.

**What can and cannot do it.** gRPC reports its outcome in HTTP/2 trailers, and `java.net.http.HttpResponse` exposes
none, so the [`jdk`](../../http-clients/jdk) client cannot serve gRPC at all — no configuration changes that. Use
[`okhttp`](../../http-clients/okhttp) or [`jetty`](../../http-clients/jetty), which these examples already carry.
**Metrics stay on HTTP regardless**: micrometer's OTLP registry has no gRPC sender to plug into.

**Logs.** Set the transport and give the logger a client to call through — there is no default, because there is
nothing safe to default to:

```java
OtlpMessagesLoggerSettings.builder()
        .otlpEndpointUrl("http://collector:4317")     // the authority, with no /v1/logs on it
        .transport(OtlpTransport.GRPC)
        .grpcSenderFactory(JettyGrpcSender::new)
        .build();
```

Note the endpoint: OTLP/HTTP appends `/v1/logs` to it, while gRPC addresses a method
(`/opentelemetry.proto.collector.logs.v1.LogsService/Export`) against the authority. The default collector port for
gRPC is **4317**, against 4318 for HTTP — the `-oe` option takes whichever you point it at.

Under Spring Boot the same two settings are `transport: GRPC` and `grpc-sender-factory-bean`.

**Traces.** Set `otlpEndpointType(GRPC)` on the tracing settings, add the
`staffix-monitoring-tracing-otlp-sender-grpc` module, and name the client with a system property:

```bash
-Dorg.lolaf.staffix.otlp.grpcSenderFactory=com.example.JettyGrpcSenderFactory
```

That module is separate from `staffix-monitoring-tracing-otlp-sender` on purpose: registering a `GrpcSenderProvider`
is classpath-global, so it reaches only the applications that ask for it. Leave the property unset with `GRPC`
selected and the engine refuses to start rather than dropping spans quietly.

**One thing gRPC gives you that HTTP does not here.** A collector that accepts a batch and then throws part of it away
says so in the export response, and the gRPC logger transport reads it — you get a warning naming how many records
were rejected. On HTTP that response body is never read, because `HttpSender` returns a status and deliberately does
not materialise a body on a successful publish.

## Publishing OTLP/HTTP over HTTP/2

`-ohv HTTP_2` makes the OTLP HTTP senders speak HTTP/2 instead of the HTTP/1.1 they pin by default. All three paths
follow it, gRPC being unaffected since it is HTTP/2 already.

Two things to know before using it:

- **On cleartext it is prior knowledge**, so it fails against an HTTP/1.1 collector rather than degrading to one.
  Over TLS it is an ALPN preference with HTTP/1.1 behind it, so it is safe there.
- **It costs more.** HTTP/2 allocates 1.17–1.51× per publish what HTTP/1.1 does and adds 17–40% latency, on every
  client and transport measured, because an OTLP export is one small POST with nothing to multiplex. That is why
  HTTP/1.1 is the default.

In an application the version is a constructor argument on the sender rather than a field of `HttpSenderSettings`,
because each client asks for it differently — so it is chosen where the sender factory is built:

```java
.httpSenderFactory(settings -> new JettyHttpSender(settings, null, HttpVersion.HTTP_2))
```

Under Spring Boot the same applies: there is no `http-version` property, and the version is chosen inside the bean
named by `http-sender-factory-bean`.

## Troubleshooting

- **Dashboard empty** — check the example is actually exporting: without `-em` there are no metrics, and without
  `-lt=OTLP` no logs. Prometheus scrapes the collector every 2s, so allow a few seconds.
- **Nothing in Loki but metrics are fine** — the log panel is scoped by the `serviceName` variable; pick
  `MetricsLogsTracesExample`.
- **401/403 against Grafana Cloud** — `-oa` needs the base64 `instanceID:apiToken`, see above.
- **Port 4318 already in use** — another collector is running; `docker compose down` in the grafana folder.
