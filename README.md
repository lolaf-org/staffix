<p align="center">
  <img src="http://lolaf.org/staffix.png" alt="Staffix" width="320">
</p>

# Staffix

[![build](https://github.com/lolaf-org/staffix/actions/workflows/build.yml/badge.svg)](https://github.com/lolaf-org/staffix/actions/workflows/build.yml)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE.txt)

**Streaming API for FIX**: a FIX engine for Java that treats latency as a correctness property.

Staffix is a full FIX protocol implementation for Java 11 and up: session layer, type-safe message encoders for every
FIX version from 4.2 to FIX Latest, persistence, logging, monitoring and administration. It is an ordinary library: in
your process, on your threads, with no media driver and no second process to operate.

- **19.5 µs round trip**, against QuickFIX/J's 37.1 on identical settings, and 11.2 µs busy-spinning
- **Under one byte allocated per message**, against QuickFIX/J's sixteen kilobytes

See [Performance](docs/performance.md) for the full measurements and how they are reached, and
[Compared to other FIX engines](docs/comparisons.md) for QuickFIX/J and Artio.

## What you get

- **Protocol**: FIX 4.2 to FIX Latest and the FIXT.1.1 session layer, with type-safe generated encoders.
- **Session layer**: initiator and acceptor, resend handling, gap fill, heartbeats, cancel-on-disconnect.
- **Persistence and logging**: in memory, file, JDBC, SLF4J and OTLP, each optionally behind an off-heap async
  handoff that keeps the I/O off the session thread.
- **Configuration**: in memory or YAML, with a JSON schema your editor validates against.
- **Administration**: logon, logout, sequence resets and settings reload at runtime, over JMX or HTTP.
- **Monitoring**: Micrometer metrics, OpenTelemetry tracing and Grafana dashboards.
- **Extension points**: SPIs for stores, loggers, settings stores, admin exporters and session plugins.
- **Integration**: a plain application factory or a Spring Boot starter.
- **Runtime**: Java 11 source and target, running on JDK 11 through 25.

## Why another FIX engine

The FIX engine is not a peripheral. It is the first thing every tick touches on the way in and the last thing every
order touches on the way out: it sits inside your tick-to-trade twice, and there is no path around it.

You can spend months tuning a trading system: lock-free structures, allocation-free hot paths, GC pauses hunted down,
threads pinned to cores. Then you put QuickFIX/J at the boundary, and every message goes through something that
allocates sixteen kilobytes and adds eighteen microseconds per round trip, its garbage landing in the heap you tuned.
**A low-latency system with a high-latency FIX engine at its edge is not a low-latency system.**

In open source Java, you currently have to choose. **QuickFIX/J is a library, and it is not low latency**: it was
designed when a millisecond was a small number, and no tuning removes the garbage. **Artio is low latency, and it is not
a library**: it is an Aeron deployment, with a media driver, an archive and log directories to operate. Both are good at
what they are; see [Compared to other FIX engines](docs/comparisons.md) for the evidence.

**Staffix closes that gap: a true low-latency FIX engine that is an ordinary Java library.** A dependency, in your
process, on your threads, built to one rule: **the hot path is a budget, and every cycle spent there has to justify
itself.** A new validation either costs nothing when switched off, or it does not ship.

## Getting started

Build it from source with `mvn clean install` at the repository root; the coordinates below then resolve from your
local repository.

The quickest look at a working session is the quickstart: an acceptor and an initiator in one process, a QuoteRequest
sent and a Quote returned:

```bash
mvn compile exec:java -pl examples/quickstart \
    -Dexec.mainClass=org.lolaf.staffix.examples.quickstart.QuickstartExample
```

[`QuickstartExample`](examples/quickstart/src/main/java/org/lolaf/staffix/examples/quickstart/QuickstartExample.java) is
the whole of what a first Staffix program needs, with nothing hidden in a base class. The other worked examples are
indexed in [`examples/`](examples/README.md): a session plugin adding a field the application never wrote, a trading
example, a market data streamer, a quote request client, YAML session settings, a Spring Boot application, and one wired
up with full monitoring.

### In your own project

The dependencies for a first application are the engine and an implementation of each pluggable part:

```xml

<dependency>
    <groupId>org.lolaf.staffix</groupId>
    <artifactId>staffix-impl</artifactId>
    <version>${staffix.version}</version>
</dependency>
<dependency>
    <groupId>org.lolaf.staffix</groupId>
    <artifactId>staffix-application-factory-simple</artifactId>
    <version>${staffix.version}</version>
</dependency>
<dependency>
    <groupId>org.lolaf.staffix</groupId>
    <artifactId>staffix-messages-store-memory-impl</artifactId>
    <version>${staffix.version}</version>
</dependency>
<dependency>
    <groupId>org.lolaf.staffix</groupId>
    <artifactId>staffix-sessions-settings-store-memory-impl</artifactId>
    <version>${staffix.version}</version>
</dependency>
<dependency>
    <groupId>org.lolaf.staffix</groupId>
    <artifactId>staffix-messages-logger-slf4j-impl</artifactId>
    <version>${staffix.version}</version>
</dependency>
```

The encoders for the FIX version you speak are generated into your build, from the dictionary the matching
`staffix-fix-*` artifact carries:

```xml
<plugin>
    <groupId>org.lolaf.staffix</groupId>
    <artifactId>staffix-fix-encoders-generator-maven-plugin</artifactId>
    <version>${staffix.version}</version>
    <configuration>
        <dictionaryFile>classpath:FIX44.xml</dictionaryFile>
        <sourcesOutputDirectory>${project.build.directory}/generated-sources/fix</sourcesOutputDirectory>
        <packageName>org.lolaf.staffix.fix44</packageName>
    </configuration>
    <executions>
        <execution>
            <goals><goal>code-generator</goal></goals>
        </execution>
    </executions>
    <dependencies>
        <dependency>
            <groupId>org.lolaf.staffix</groupId>
            <artifactId>staffix-fix-44</artifactId>
            <version>${staffix.version}</version>
        </dependency>
    </dependencies>
</plugin>
```

[FIX versions and dictionaries](docs/fix-versions-and-dictionaries.md) lists the versions and explains the options.

Then build an engine, hand it an application per session, and start an acceptor or an initiator from it.
[`QuickstartExample`](examples/quickstart/src/main/java/org/lolaf/staffix/examples/quickstart/QuickstartExample.java)
is that program, and it is the one the command above runs.

## Documentation

Everything else is in the [documentation](docs/README.md).

## Is it production ready?

Not yet proven in production: Staffix is **0.9.0**, and no one runs it on a live trading session today. Every build
runs the engine's own suite, 22 of the official FIX session conformance scenarios, and a real QuickFIX/J engine driven
against Staffix in both roles; a soak test trades Staffix against QuickFIX/J continuously over a real network. The API
is settled, and any breaking change is listed in the [changelog](CHANGELOG.md).

Support is community only, on a best-effort basis: [issues](https://github.com/lolaf-org/staffix/issues) are read and
answered, with no guaranteed response time.

## Licence

Apache License 2.0.
