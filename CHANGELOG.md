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

## [0.9.2] - WIP

### Added

- **High availability.** Two engines run the same sessions: one leads and runs them, the other is a standby that
  receives every write to their stores and takes them over when the leader fails. Each session chooses `NONE`,
  `ASYNC` (nothing waits) or `SYNC` (a message reaches the counterparty only once the standby has it), with
  `haSessionSettings.replicationMode`. The leader is elected by primos with a witness, or by an election of your own.
  A planned switchover logs the sessions out and the other engine carries on at the same sequence numbers; after a
  failure the new leader moves its sequence numbers ahead and answers the gap with a gap fill. A leader cut off from
  the others drops its sessions a fencing margin (50 ms by default) before the other engine can be elected. Two
  engines sharing one database or directory are detected and not replicated into. Modules `staffix-ha-impl`,
  `staffix-ha-primos`, `staffix-ha-spring-boot` and `staffix-ha-primos-spring-boot`; see [High availability](docs/high-availability.md),
  including what it does not do.
- **The high availability can be watched and driven** through the admin API, HTTP and JMX: its status, a
  switchover, maintenance mode and a re-sync of the standby; it publishes `ha.*` meters and alerts when the standby
  is missing, out of sync or behind.
- **The engine's own components have meters and probes**, beside the sessions': a monitoring manager that supports
  it (Micrometer's does) publishes engine meters, listed by the HTTP admin API's `GET engine/meters`, and their probes
  are listed and cleared under `engine/{component}/probes`, or through JMX.
- **Applications can publish counters**, with `FixSessionsMonitoringContext.getCounter`.
- **A counter, timer or gauge can raise its own alerts**: add an `AlertingCounter`, `AlertingTimer` or `AlertingGauge`
  with `Probes.watch`, and what the application records is both published and alerted on. A timer alerts
  on a latency percentile, so a short spike does not alert; a gauge alerts above or below thresholds.
- **The file messages logger has a choice of line format**, set with `lineFormat`. The plain format, the default,
  writes the same lines as before. The new logfmt format (`LogfmtFileLogLineFormat`) starts each line with the time,
  the engine, instance and session, the direction and the message type as logfmt pairs, then the message: a log
  shipper such as the OpenTelemetry Collector can then send the file's records to Loki and the admin UI with the
  same attributes as the OTLP messages logger. With Spring Boot, `line-format=logfmt`. A format of your own
  implements `FileLogLineFormat`, set in Java or named by `line-format-bean`.
- **An evaluator can forget what it measured when its session logs back in**, by overriding
  `ProbeEvaluator.reset()`. The evaluators and alerting meters staffix provides already do.
- **The admin API, HTTP and JMX, shows whether a probe runs only while logged in** (`onlyWhileLoggedIn`), and
  whether it is currently waiting for a logon (`suspended`).

### Changed

- **An encoder that is not reusable now refuses a second message**: `begin()` throws `IllegalStateException` once its
  first message was sent. Before, reusing it silently worked with the default heap buffers, and crashed the JVM with
  direct buffers. To send several messages with one encoder, take it from a pool or call `asReusable()`.
- **Probes an application adds are now evaluated only while their session is logged in.** At logout their
  ongoing alerts are cleared, and at logon they start afresh, so a probe on the order flow no longer alerts all
  night, or the moment the session is back. An application that removed its probes on logout and added them again
  on logon can add them once. For a probe that must keep running while logged out, such as one watching a disk or a
  queue, build it with `onlyWhileLoggedIn(false)`. The built-in probes are unchanged.
- **`ProbeState.getLastEvaluation()` returns an `Optional<Instant>`**, empty for a probe whose session logged out
  before it was ever evaluated: such a probe now shows as suspended in the admin API instead of not at all. In the
  HTTP and JMX admin APIs, `lastEvaluation` is absent in that case.
- **The file messages logger's line prefixes belong to the plain line format**: `logInPrefix`, `logOutPrefix` and
  `logEventPrefix` moved from `FileMessagesLoggerSettings` to `PlainFileLogLineFormat`, and their Spring Boot
  properties under `plain.`, for example `staffix.messages-loggers-file.instances.<id>.plain.log-in-prefix`.
- **The OTLP messages logger gives a message whose type the dictionary does not declare**, such as a user defined
  one, its `fix.mt` attribute, which it used to leave out.

- **FIX 4.3 treats BusinessMessageReject as an application message**, as every other FIX version does. A business
  reject the session sends now goes through `onMessageEncoding` rather than `onAdminMessageEncoding`.
- **Generated FIX packages no longer contain a `BusinessMessageRejectEncoder`**: send a business reject with
  `FixSession.sendBusinessMessageReject`.
- **A dictionary cut from Orchestra keeps BusinessMessageReject** whatever `includeMessagesFile` lists, since the
  session layer needs it.
- **Stopping the engine no longer closes the `MeterRegistry` the application gives Micrometer monitoring** (with
  Spring Boot, its `MeterRegistry` bean): the application closes what it creates. To close it when the engine stops,
  with a last publish, pass `MeterRegistry::close` as `stoppingMeterRegistryConsumer`. The OTLP variant still closes
  the registry it builds.

- **Probes and alerts belong to a scope**, a session or one of the engine's components, so the alerting API no longer
  assumes a session. `FixSessionProbes` is now `Probes` and `SessionProbeState` is `ScopedProbeState`; the
  `AdminApi` probe methods take a `ProbeScope` (`FixSessionProbeScope.of(sessionId)` for a session) or a `ProbeId`;
  `AlertEvent` gives `getSource()` and `getProbeId()` instead of `getEngineId()`, `getFixSessionId()` and
  `getProbeName()`. An alerts publisher of your own implements `AlertsPublisher`.
- **The Alertmanager label `engine` is now `source`**, still the engine's instance id: update the routes and
  silences that match on it. A session's alerts keep `fixSessionGroup` and `fixSessionName`; a component's carry
  `component` instead.
- **The HTTP admin API's `GET meters` is now `GET sessions/meters`**, beside the new `GET engine/meters`.

### Removed

- **`FixSessionId.invert()`**.

### Fixed

- An initiator could log on with its SenderCompID and TargetCompID swapped, and be refused as an unknown session,
  when an acceptor in the same JVM had created the opposite session first, as a loopback test does.
- A session whose dictionary lacks BusinessMessageReject, or any session message, failed to start with a
  `NullPointerException`; it is now refused with an error naming the missing message and the dictionary.
- A project depending only on `staffix-spring-boot-starter` failed to start with "Unable to find any SPI instance" for
  `FixEngineBuilder`: the starter now brings the engine, as the Spring Boot guide says.
- An async messages store or logger configured in Java without a queue directory, or without what it wraps, failed with
  a `NullPointerException`; it is now refused with an error saying what is missing.
- A JDBC messages store behind the async store, with `maxMessagesPerSession` set, failed every session's start with
  a `NullPointerException`; and an OTLP messages logger behind the async logger kept its publishing thread after the
  engine stopped. The async store and logger now start and stop what they wrap.
- The JVM warmup failed to start its sessions when the engine builder it copies registers its alerts publisher under
  another id than `default`; its sessions now use that publisher.
- The session settings JSON Schema accepted `P`, `PT` and `P1DT` as durations, which then failed when the file was
  loaded; an editor using the schema now flags them.
- A session loaded from a YAML file that did not set `messageEncodersDirectByteBuffers` or
  `pooledMessageEncodersDirectByteBuffers` encoded into heap buffers, where the default is direct buffers; and
  `advertiseMsgTypeGrpOnLogon`, `advertiseEngineOnLogon`, `advertiseApplicationOnLogon` and those two set in a
  `default.yaml` were ignored for a session file that left them out. A setting left out of a session file now takes
  the `default.yaml` value, then the default.
- The memory messages store could resend a message with another message's content when a resend ran while the session
  kept sending, and with direct memory, the default, could read memory it had already freed. A message overwritten
  during a resend is now gap filled instead.

## [0.9.1] - 2026-10-14

### Added

- **An HTTP admin API** (`staffix-admin-api-http-impl`, `staffix-admin-api-http-spring-boot`) serves every session's
  state, settings, meters and operations as JSON, behind a bearer token, for the
  [staffix admin console](https://github.com/lolaf-org/staffix-admin).
- **Sessions can be added, edited and removed at runtime**, through `AdminApi` or the HTTP admin API.
- **`AdminApi` lists an acceptor's sessions, the components a session can use and each session's meters**, and
  `sendFixMessages` sends several messages in order.
- **`FixSession.getStatus()`** says whether a session is logged in, or logged out by an operator, outside session time
  or inside it.
- **Secret session settings**, declared with `FixApplicationSessionSettingDescriptor.secret`, are never shown by the
  HTTP admin API.
- **An application can publish gauges** through `FixSessionsMonitoringContext.getGauge`.
- **Metrics, logs and traces carry the engine's id** (`fix.eid`), so same-named sessions in different engines can be
  told apart.
- **A generated FIX package ships its dictionary**, so the admin console can decode a session's messages.
- **Alerting.** Each session's probes are checked every second and their alerts sent to an alerts publisher. An
  application adds its own probes and raises its own alerts, by hand or on a rate of events; a probe can wait before
  raising, hold an alert through flapping, and carry labels and a runbook URL. See [Alerting](docs/alerting.md).
- **Built-in alerts** when a messages store or logger fails, when an async queue grows too deep, and when a session
  stays down inside its session time. A store or logger found down is skipped until it comes back, rather than
  blocking the session. See [When a store or logger fails](docs/stores-and-loggers.md#when-a-store-or-logger-fails).
- **An operator can clear an alert by hand**, over HTTP or JMX.
- **SLF4J and Prometheus Alertmanager alerts publishers**, each with a Spring Boot module.
- **Admin tools can see probes and alerts**, through `AdminApi`, the HTTP admin API and JMX.
- **A JDBC session settings store** (`staffix-sessions-settings-store-jdbc-impl`,
  `staffix-sessions-settings-store-jdbc-spring-boot`) keeps sessions in a database table that several engines can
  share. See [The JDBC store](docs/session-settings-stores.md#the-jdbc-store).

### Changed

- **A session is identified by its group and its name**, so two counterparties can each have a `trading` session.
  `FixSessionId.getId()` is now `getName()`, settings say `name` instead of `id`, and the group appears wherever a
  session is named: files, metrics, actuator paths, JMX.
- **A `FixApplication` declares its dictionary** with `getDictionaryId()`; a session whose FIX version does not match
  is refused when configured.
- **An initiator held logged out no longer connects**; it waits for `logon()`.
- **The desired state is `FixSessionDesiredState`, `LOGGED_IN` or `LOGGED_OUT`.** `CONNECTED` and `DISCONNECTED` are
  gone, and an acceptor held logged out answers a Logon with a Logout.
- **`FixSessionState` is no longer public**: use `isLoggedIn()`, `isConnected()` and `getStatus()`.
- **The `session.logon.state` gauge is now `session.logon.status`**, and the actuator reports `logonStatus`.
- **Message attributes are shorter and the same on metrics and logs**: `fix.mt` and `fix.md` (`i` or `o`).
- **Custom extensions have new methods to implement**: the engine's id and the session's probes are passed to
  messages stores, loggers and session plugins; settings stores implement `isPersistent()` and `bind(engineId)` (done
  for you by `AbstractFixSessionSettingsStore`); and messages stores must keep their sequence numbers in memory, never
  failing to return them.
- **The async store's queue thresholds are one depth per alert level** (`QueueSizeThresholdsSettings.thresholds`).
- **`FixInitiatorTargets` names the main target and the backups.**
- **Session settings files say `withinSessionTimeCheckInterval`**, like the Java and Spring Boot settings.
- **Session settings files must declare `schemaVersion: 1`**: add it to each YAML file, `default.yaml` included.
- **The session settings JSON schema documents every field and its default**, and ships in
  `staffix-sessions-settings-document` (classifier `schema`).
- **The HTTP admin API's `POST .../messages` takes a list.**
- **The DDL generator plugin is now `staffix-jdbc-ddl-generator-maven-plugin`**, and its required `schema` parameter
  picks the store: `MESSAGES` or `SESSIONS_SETTINGS`.
- **The OTLP messages logger needs protobuf-java 4.36.2 or later.** Spring Boot 4.1.1 downgrades it: set
  `protobuf-java.version` to 4.36.2.

### Removed

- **The `dictionaryId` session setting**: a session speaks its application's dictionary.
- **`FixSession.disconnect(String)`**: use `logoutPermanently(String)`.
- **`AdminApi.ResetFixSessionMode.LOGOUT_LOGON_REST_NUM_FLAG`**: use `LOGOUT_LOGON_RESET_NUM_FLAG`.
- **The async store's queue and resource listeners**: the same conditions are now alerts.

### Fixed

- The DDL generator plugin ignored `tablePrefix`.
- Two sessions with the same name in different groups overwrote each other's state, logs and metrics.
- Listing an acceptor's sessions during a settings reload could fail or return a partial list.
- A settings update the file store refused could leave the store out of step with the file.
- A session could fail to start with "Fix session application settings ... is missing" although it was configured.
- The Spring application factory could miss an application's `destroy()` call when sessions were created
  concurrently.
- The error for an unknown message store listed the settings stores.
- A disconnected session's status and health were wrong until it reconnected.
- An acceptor kept a connection that never sent a Logon open forever; it now closes it after
  `FixAcceptorBuilder.logonTimeout`.
- An acceptor refused a Logon split across several TCP segments.
- A Logon validated after its connection closed could log on the session's next connection.
- When the engine stopped, a push metrics registry could keep the session's last status instead of logged out.
- The OTLP messages logger could mislabel a session event as a message, or repeat a message's attributes.
- When an async store's or logger's database went down, every other session sharing its reader thread stopped
  persisting for a second.
- The async store and logger could lose messages around a database outage: a write failing just as the database came
  back, or a replay failing while it was still unstable, was dropped instead of retried.
- Stopping the engine while an async store's database was down could leak a thread.

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
