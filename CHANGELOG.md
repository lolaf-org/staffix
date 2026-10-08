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

## [0.9.1] - 2026-10-01

### Added

- `AdminApi.getAcceptorsSessions()` lists each acceptor with the sessions it accepts, the counterpart of
  `getInitiatorsTargets()`, so an administration tool can tell which acceptor a session belongs to.
- An HTTP admin API (`staffix-admin-api-http-impl`, and `staffix-admin-api-http-spring-boot` for Spring Boot) serves
  every session's state, settings and operations as JSON, over HTTP or HTTPS, behind a bearer token (and, optionally, a read-only one
  for monitoring tools), and announces the engine to the
  [staffix admin console](https://github.com/lolaf-org/staffix-admin), which can then monitor and operate it.
- A generated FIX package now carries its dictionary as `staffix-dictionaries/<dictionaryId>-<version>.xml`, so the
  HTTP admin API can serve it and the console decodes a session's messages with the dictionary that session runs.
- Metrics, FIX message logs and traces carry `fix.eid`, the engine's id, so a session can be told apart from a
  same-named one in another engine. The Grafana dashboard filters by engine, and its FIX logs panel follows the
  engine, group and session selectors.
- The monitoring stack in `monitoring/grafana` runs Loki 3.7.8 and indexes `fix.eid`, `fix.sg` and `fix.sn`, so a log
  query for one engine, group or session reads only that session's logs instead of scanning them all.
- **The HTTP admin API edits sessions**: `GET schemas/session-settings` describes a session's settings document,
  `GET` / `PUT` / `DELETE sessions/{group}/{name}/settings` read, replace and remove a session's settings,
  `POST session-settings-stores/{id}/sessions` adds one, and `sessions/{group}/{name}/application-settings` lists the
  application settings its application declares. `settings` now answers that document, with the session's stored
  values rather than the Java settings class's fields; secret values are masked and kept when sent back masked.
- **`AdminApi` adds, updates and removes a session's settings** in the store that holds them
  (`addFixSessionSettings`, `updateFixSessionSettings`, `removeFixSessionSettings`), tells which store holds a session
  and whether that store keeps changes across a restart, and lists the application settings a session's application
  declares. An update may change the session's CompIDs; a name already used in the engine is refused.
- **An application can declare a session setting secret** with `FixApplicationSessionSettingDescriptor.secret(id,
  description)`, such as a Logon password: the HTTP admin API never shows its value, whatever its name.
- **Adding or updating a session's settings refuses an application factory, application, message store, messages
  logger or session plugin the engine does not have**, instead of accepting settings the session then fails to start
  with.
- **An application publishes gauges with its session's metrics**: `FixSessionsMonitoringContext.getGauge(id,
  description, tags)` gives a gauge the application sets or adds to, from any thread and without allocating, and
  `getGauge(id, description, tags, supplier)` one read from the supplier at each export, for a value the application
  already holds. Both are listed among the session's meters.
- **A session's monitoring describes the meters it publishes**: `FixSessionsMonitoringContext.getMeterDescriptors()`
  lists the built-in meters switched on and the custom timers the application obtained from `getTimer`, each with
  its description, the tags that split it into series and whether quantiles can be computed;
  `AdminApi.getFixSessionMeters()` and the HTTP admin API's `GET meters` and `GET sessions/{group}/{name}/meters`
  serve them. The built-in meters' descriptions now say exactly what each one measures.
- **An admin tool can offer a session's components to choose from**: `AdminApi.getFixSessionComponents()` and the
  HTTP admin API's `GET session-components` list the application factories with the applications each serves, the
  message stores, the messages loggers and the session plugins with the plugin types they serve, under the ids a
  session's settings name them by.
- **`AdminApi.sendFixMessages` sends several messages in order** on a session. It stops at the first one the session
  refuses, those before it having gone out, and the error says which one it was ("Message 2 of 3: ...").

### Changed

- **A session is identified by its group and its name.** A session name is unique only within its group, so two
  counterparties can each have a `trading` session. `FixSessionId` exposes `getName()` (formerly `getId()`, and
  `.name(...)` on its builder) and `getQualifiedName()`, `group.name`, which is unique in the engine. Session
  settings files and Spring Boot properties say `name` instead of `id`, and a session without a group belongs to
  `default`.
- **Stored state and files are named after the qualified name**, for example `alpha.trading.bin` for the file
  message store, and the same key in the JDBC store, the async store queues, the file logger and the session
  settings files.
- **Monitoring attributes follow the same naming:** `fix.sn` (session name) and `fix.sg` (group)
- **A custom `FixMessagesLogger` receives the engine's id:** `getLogger(fixEngineId, fixInstanceId, fixSessionId,
  messageTypeRegistry)`.
- **A `FixSessionsPlugin` receives the engine's id:** `onSessionCreated(fixEngineId, fixInstanceId, fixSession,
  incomingMessageTypes, outgoingMessageTypes)`.
- **Management endpoints include the group:** one session is at `/actuator/fix-sessions/{group}/{name}`, the
  health details are keyed `group.name`, and each JMX session bean name has a `group` key.
- **Session settings files say `withinSessionTimeCheckInterval`** under `sessionScheduleSettings`, the name the
  Java settings and the Spring Boot properties already use, instead of `withinSessionCheckInterval`.
- **The session settings JSON schema describes every field and gives its default**, so an editor shows what a
  setting does and what it is when left out.
- **A custom `FixSessionsMonitoringContext` implements `getMeterDescriptors()`** (the meters created through it) **and
  both `getGauge` methods**.
- **A custom `FixApplicationFactory` implements `getApplicationIds()`**: the application ids its `getInstance`
  accepts. It may also override `getDictionaryId(applicationId)`, which the engine uses to check settings without
  binding an application; the default asks `getInstance`.
- **A custom `FixSessionsSettingsStore` implements `isPersistent()`**: whether a change made through it survives a
  restart.
- **`FixInitiatorTargets` names the main target and the backups** (`getMainTarget()`, `getBackupTargets()`);
  `getTargets()` still lists them all, main first.
- **The HTTP admin API's `POST sessions/{group}/{name}/messages` takes a list**, `messages`, instead of a single
  `message`, and sends them in order.
- **A message sent through the admin API may start or end with whitespace**, such as the line break of a message
  pasted from a log; it is ignored instead of making the message invalid.
- **A `FixApplication` declares the dictionary it speaks** with `getDictionaryId()`, for example
  `FixDictionaryId.of(FixDictionaryId.DEFAULT_ID, FixRegularVersion.VERSION_44)`, and its sessions use it. A session
  whose FIX version, or for FIXT its DefaultApplVerID, is not the application's is refused when its settings are
  added or updated, when its initiator is created and when it starts, instead of failing at its first message. A
  FIXT backup target is accepted when its DefaultApplVerID is the main target's version.
- **The session settings document has its own module, `staffix-sessions-settings-document`**: the model a session
  file is read into, with its JSON schema, for tools that read or write session settings outside the file store.
  The schema is published from it (classifier `schema`) rather than from `staffix-sessions-settings-store-file-impl`.
- **An initiator that is to stay logged out no longer connects.** Logged out with `logoutPermanently()` (the admin
  API's logout) or configured `desiredSessionState: LOGGED_OUT`, it used to dial and hold a connection without ever
  logging on, which an acceptor with a logon timeout dropped and the initiator dialed again, over and over. It now
  waits, as a `DISCONNECTED` one does, and dials again when `logon()` is called.
- **`desiredSessionState: CONNECTED` is refused** when the settings are validated: a session held connected but not
  logged on had nothing to do on its connection.

### Removed

- **The `dictionaryId` session setting**, in `FixSessionSettings`, session settings files and the in-memory store's
  Spring Boot properties: a session speaks its application's dictionary. A settings file that still sets it is
  refused.
- `AdminApi.ResetFixSessionMode.LOGOUT_LOGON_REST_NUM_FLAG`, a misspelling: use `LOGOUT_LOGON_RESET_NUM_FLAG`, which
  behaves the same.

### Fixed

- Two sessions with the same name in different groups no longer share stored state, log files, metrics, JMX beans
  or health entries; before, they overwrote each other.
- Reading an acceptor's sessions while its settings store reloads (`getConfiguredSessionsSettings()`, the admin
  API) could fail or return a partial list. `getConfiguredSessionsSettings()` now returns a snapshot.
- An update a file settings store refuses, such as a change to a value a `${...}` placeholder sets, no longer leaves
  the store holding the refused settings while the session and the file keep the old ones.
- A session could fail to start with "Fix session application settings ... is missing" although its configuration
  gave the setting, when the application described that setting only after the configuration was read.
- An application from the Spring application factory could miss its `destroy()` call at shutdown, or the shutdown
  could fail, when sessions were being created on several threads at once.
- The error for a session naming an unknown message store listed the session settings stores instead of the message
  stores to choose from.
- `FixSession.isWithinSessionTime()` kept its last answer while the session was disconnected: a session down when
  its window closed still read as within session time, and one down when it opened as outside it, until it connected
  again. It now follows the schedule while disconnected too, and so do the HTTP admin API's session status and the
  actuator's health.
- An acceptor kept a connection that never sent a Logon open for as long as the client liked, so any client reaching
  the port could hold sockets open. It now closes one that sends no Logon within `FixAcceptorBuilder.logonTimeout`,
  10 seconds by default (`staffix.acceptors.<name>.logon-timeout` with Spring Boot, zero to never close it).
- An acceptor refused a Logon that reached it in several TCP segments as an unknown session, which a slow link, a
  proxy or a client writing in pieces can cause. It now waits for the whole Logon.
- A Logon whose validation (`FixApplication.validateLogon`) finished after its connection had closed could log on the
  session's next connection, whatever that connection's own Logon said. The outcome of a validation now applies only to
  the connection that sent the Logon.

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
