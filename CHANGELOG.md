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

## [0.9.1] - 2026-10-09

### Added

- **An HTTP admin API** (`staffix-admin-api-http-impl`, and `staffix-admin-api-http-spring-boot` for Spring Boot)
  serves every session's state, settings, meters and operations as JSON over HTTP or HTTPS, behind a bearer token
  and an optional read-only one. It announces the engine to the
  [staffix admin console](https://github.com/lolaf-org/staffix-admin), which can then monitor and operate it.
- **Sessions can be added, edited and removed at runtime**, through `AdminApi` or the HTTP admin API, in the settings
  store that holds them. Settings naming a component the engine does not have are refused up front.
- **`AdminApi` lists what an admin tool needs**: each acceptor's sessions (`getAcceptorsSessions()`), the components a
  session can use (`getFixSessionComponents()`), and each session's meters (`getFixSessionMeters()`).
- **`AdminApi.sendFixMessages` sends several messages in order**, stopping at the first one the session refuses.
- **`FixSession.getStatus()`** tells an operator where a session stands: `LOGGED_IN`, `LOGGED_OUT_BY_OPERATOR`,
  `LOGGED_OUT_OUTSIDE_SESSION_TIME` (a planned pause) or `LOGGED_OUT_INSIDE_SESSION_TIME` (the one to alert on).
- **An application can declare a session setting secret**, such as a Logon password, with
  `FixApplicationSessionSettingDescriptor.secret(id, description)`; the HTTP admin API never shows its value.
- **An application can publish gauges** with its session's metrics, through `FixSessionsMonitoringContext.getGauge`.
- **Metrics, logs and traces carry the engine's id** (`fix.eid`), so same-named sessions in different engines can be
  told apart. The Grafana dashboard filters by engine, and Loki indexes engine, group and session.
- **A generated FIX package ships its dictionary**, so the admin console can decode a session's messages.

### Changed

- **A session is identified by its group and its name**, so two counterparties can each have a `trading` session.
  `FixSessionId.getId()` is now `getName()`, and `getQualifiedName()` returns `group.name`. Settings files and Spring
  Boot properties say `name` instead of `id`; a session without a group belongs to `default`.
- **The group appears everywhere a session is named**: stored state and log files (`alpha.trading.bin`), monitoring
  attributes (`fix.sg`, `fix.sn`), actuator paths (`/actuator/fix-sessions/{group}/{name}`), health keys and JMX
  bean names.
- **A `FixApplication` declares its dictionary** with `getDictionaryId()`, and its sessions use it. A session whose
  FIX version does not match is refused when configured or started, instead of failing at its first message.
- **An initiator held logged out no longer connects.** It used to dial and hold a connection without logging on, which
  an acceptor kept dropping. It now waits for `logon()`.
- **The desired state is `FixSessionDesiredState`, with only `LOGGED_IN` and `LOGGED_OUT`.** `CONNECTED` and
  `DISCONNECTED` are gone: use `LOGGED_OUT`. An acceptor held logged out now answers a Logon with a Logout giving the
  reason, instead of silently closing the connection.
- **`FixSessionState` is no longer public**: use `isLoggedIn()`, `isConnected()` and `getStatus()`.
- **The `session.logon.state` gauge is now `session.logon.status`**: 1 logged in, 0 logged out inside session time,
  2 outside session time, 3 by an operator. The actuator reports `logonStatus` instead of `state`.
- **Shorter message attributes, the same on metrics and logs**: `fix.msg.type` is now `fix.mt` and `fix.msg.dir` is
  now `fix.md`, with `i` or `o` instead of `in` or `out`. OTLP message
  logs carry `fix.md` instead of `fix.log.type`, so one filter selects a direction in both; a session event carries
  no `fix.md`. `FixMessageDirection` lists its values for tools reading them.
- **Custom extensions have new methods to implement**: `FixMessagesLogger.getLogger` and
  `FixSessionsPlugin.onSessionCreated` receive the engine's id, `FixApplicationFactory` implements
  `getApplicationIds()`, `FixSessionsSettingsStore` implements `isPersistent()`, and `FixSessionsMonitoringContext`
  implements `getMeterDescriptors()` and both `getGauge` methods.
- **`FixInitiatorTargets` names the main target and the backups** (`getMainTarget()`, `getBackupTargets()`).
- **Session settings files say `withinSessionTimeCheckInterval`** instead of `withinSessionCheckInterval`, like the
  Java and Spring Boot settings.
- **The session settings JSON schema documents every field and its default**, and is now published from
  `staffix-sessions-settings-document` (classifier `schema`).
- **The HTTP admin API's `POST .../messages` takes a list** (`messages`), and surrounding whitespace in a sent
  message is ignored.
- **The OTLP messages logger needs protobuf-java 4.36.2 or later**, which it and the Micrometer OTLP module now
  bring. A Spring Boot 4.1.1 application downgrades it to 4.35.1, and the logger then fails to load: set the
  `protobuf-java.version` property to 4.36.2.

### Removed

- **The `dictionaryId` session setting**: a session speaks its application's dictionary. A settings file that still
  sets it is refused.
- **`FixSession.disconnect(String)`**: use `logoutPermanently(String)`.
- **`AdminApi.ResetFixSessionMode.LOGOUT_LOGON_REST_NUM_FLAG`**, a misspelling: use `LOGOUT_LOGON_RESET_NUM_FLAG`.

### Fixed

- Two sessions with the same name in different groups overwrote each other's stored state, logs, metrics, JMX beans
  and health entries.
- Listing an acceptor's sessions while its settings store reloaded could fail or return a partial list.
- A settings update the file store refused could leave the store out of step with the session and the file.
- A session could fail to start with "Fix session application settings ... is missing" although the setting was
  configured.
- The Spring application factory could miss an application's `destroy()` call, or fail at shutdown, when sessions
  were created concurrently.
- The error for an unknown message store listed the settings stores instead of the message stores.
- `isWithinSessionTime()` froze while a session was disconnected, so its status and health were wrong until it
  reconnected.
- An acceptor kept a connection that never sent a Logon open indefinitely. It now closes it after
  `FixAcceptorBuilder.logonTimeout`, 10 seconds by default.
- An acceptor refused a Logon split across several TCP segments as an unknown session.
- A Logon validated after its connection closed could log on the session's next connection.
- When the engine stops, the last `session.logon.status` value a push registry (OTLP, Datadog...) receives is 3,
  logged out, instead of the session's last sampled status.
- The OTLP messages logger could send a session event (connected, disconnected, sequence reset...) marked as an
  incoming or outgoing message, and a message with its direction or message type twice.

## [0.9.0] - 2026-09-30

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
- **Backup targets for an initiator**, for a counterparty whose backup site uses another session id, another host,
  or both. An initiator is given a main target, a session id with the addresses to dial for it, and any number of
  backup targets of the same shape. It never moves to a backup on its own: you switch it with `FixInitiator.switchTo`,
  `AdminApi.switchInitiatorSession` or the JMX `FixAdmin` bean. A switch logs the current session out cleanly
  before dialling the other one, and each session keeps its own sequence numbers. In Spring Boot they are
  `staffix.initiators.<name>.main-target` and `backup-targets`.
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

[0.9.1]: https://github.com/lolaf-org/staffix/releases/tag/v0.9.1
[0.9.0]: https://github.com/lolaf-org/staffix/releases/tag/v0.9.0
