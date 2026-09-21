# Changelog

All notable changes to this project are recorded here, in the format of
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). This project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Each released version needs its own `## [x.y.z] - YYYY-MM-DD` heading here **before** the release is
cut: the release workflow refuses to run without one, and the GitHub Release for the tag is created
with that section as its body. Write it in the commit that precedes the release, together with the
matching `[x.y.z]:` link definition at the foot of the file.

There is deliberately no `[Unreleased]` section, which is where Keep a Changelog would collect notes
between releases. A section is written when the version it belongs to is being cut, so its heading
carries the right number and date the first time and the workflow's check has exactly one heading it
could mean.

## [0.9.0] - 2026-09-15

First public release. Staffix is a FIX engine for Java that treats latency as a correctness property: a full session
layer and type-safe encoders for every FIX version from 4.2 to FIX Latest, as an ordinary library in your process
rather than an infrastructure component to deploy. It runs on Java 11 or later, is compiled to Java 11 bytecode, and
rests on [ringos](https://github.com/lolaf-org/ringos) and [betty](https://github.com/lolaf-org/betty).

Measured against QuickFIX/J 3.0.1 on the same harness and settings: a round trip in half the time, a 99th percentile
faster than QuickFIX/J's median, and under one byte allocated per message against its sixteen kilobytes.

### Added

- **The engine.** `staffix-api` carries the abstractions — `FixInitiator`, `FixAcceptor`, `FixSession`,
  `FixApplication`, `FixMessageEncoder`, `DecodedFixMessage` — and `staffix-impl` is the session state machine:
  sequence numbers, resend handling, logon and logout, heartbeats, test requests and gap fill. Every validation
  switch is off by default and documents its own latency cost in its javadoc, so nothing optional is paid for
  unless it is asked for.
- **Generated FIX packages**, `staffix-fix-42` through `staffix-fix-50sp2`, `staffix-fixt-11` and
  `staffix-fix-latest` — type-safe encoders, field classes and message-type registries, generated from the FIX
  Trading Community's Orchestra repository cut at an exact version **and extension pack**, with the extension pack
  stated on the root element rather than left to be inferred. Deprecations the standard has declared are carried
  into `@Deprecated` on the generated fields, setters, groups and enum constants, so the compiler warns when you use
  something FIX has retired. `staffix-fix-latest` holds every application message of FIX.4.4 as FIX Latest describes
  it today; the whole standard builds under a profile flag.
- **A codec built to a budget.** `staffix-codec` parses without allocating on the happy path — comparing hashes
  rather than materialising strings, decoding fields lazily, and building strings and exceptions only on the reject
  branch — and optional behaviour is a strategy chosen once at construction rather than a branch tested per field,
  so a disabled feature is absent from the parsing loop rather than cheap in it.
- **A serde per wire type, both directions**, in `staffix-codec` (package `org.lolaf.staffix.codec.serde`): `int` and
  `long` with length-specialised and signed and unsigned paths, `double`, `boolean`, `char`, byte arrays,
  `DecimalFloat` and `BigDecimal`, `String`, `UUID`, and the six FIX temporal types. Object-valued serdes come in
  plain, cached and thread-local forms chosen per field, so the cost of every value is stated rather than assumed;
  encoding writes into a buffer you already own.
- **Message stores** — `memory`, `file`, `jdbc`, and an `async` decorator that hands the write to a Chronicle Queue
  and returns, so a slow database costs queue depth rather than round-trip time. `staffix-messages-store-test-kit`
  is published, so a store written outside this repository is held to the contract the shipped ones are.
- **Message loggers** — SLF4J, file, demux and OTLP, behind the same async decorator.
- **Session settings** in memory or in YAML, with a JSON schema generated from the model at build time and shipped
  inside the jar, so an editor completes and validates the file.
- **Administration.** `AdminApi` is the whole operational surface — log a session on or off, reset sequence state,
  read and set either side's sequence numbers, reload a settings store without a restart — and is deliberately
  independent of how it reaches the engine. `AdminApiExporter` is the SPI; JMX is the exporter that ships.
- **Monitoring.** Micrometer metrics with configurable latency timers, and OpenTelemetry tracing with W3C trace
  propagation, both exportable over OTLP, with provisioned Grafana dashboards. Both are session plugins rather than
  engine internals, and either can be wrapped to run its callbacks on their own threads or to sample them.
- **Session plugins**, which attach to every message of a session from the outside — measure it, audit it, or stamp
  a field on the way out — without the application knowing.
- **Identifier generators** — UUID v7 and v4 and Snowflake, with an allocation-free path from generation to the
  encoder for v7 and Snowflake, and the serdes to read them back off the wire the same way.
- **JVM warmup**, which runs a full loopback session against a synthetic message carrying one field of every type
  the codec knows, so the whole message path is compiled before the first real order arrives.
- **Integration** — a plain application factory, and a Spring Boot starter that declares the engine in
  `application.properties` with completion and descriptions in the IDE.

### Notes

- Java 11 or later, compiled to Java 11 bytecode, tested on JDK 11 through 25.
- Required dependencies are ringos, betty and SLF4J. Everything else is optional and arrives with the
  implementation that needs it: Chronicle Queue with the async store and logger, Hibernate and HikariCP with the
  JDBC store, Micrometer and the OpenTelemetry protobufs with the monitoring plugins.
- 22 of the official FIX Session conformance test cases from `FIX_Session_Testcases_June_2020` run as executable
  tests on every build, alongside interoperability tests that drive a real QuickFIX/J engine against Staffix
  in-process.
- All artifacts are signed, carry sources and javadoc, and are built reproducibly — the jars from a given tag are
  byte-identical to the published ones.

[0.9.0]: https://github.com/lolaf-org/staffix/releases/tag/v0.9.0
