# Staffix components

What an environment can be made of. Every component is registered on the engine under an instance id and named by
the sessions that use it; an id left out is `default`. Read the doc in the last column before configuring one: it
holds the settings, their defaults and what each costs.

Artifacts are `org.lolaf.staffix:<artifact>-impl` in plain Java and `<artifact>-spring-boot` with Spring Boot, at the
staffix version; a name marked *full* is used as it is. With Spring Boot declare the `-spring-boot` artifact only: it
brings the `-impl`, and an impl declared again, above all in test scope, hides it from the autoconfiguration, which
then skips the component without a word. The engine is the exception: staffix 0.9.1's starter does not bring
`staffix-impl`, so declare it beside the starter.

Components marked *proven* run in a reference project; for the others, the generated prod test is the proof.

## Message stores

Selected by a session's `fixMessageStoreInstanceId`. Doc: `docs/stores-and-loggers.md`.

| store | artifact | Java settings | Spring prefix | notes |
| :- | :- | :- | :- | :- |
| memory (*proven*) | `staffix-messages-store-memory` | `stores.messages.memory.MemoryMessageStoreSettings` | `staffix.messages-stores-memory.instances.<id>` | nothing survives a restart: dev, tests, sessions resetting on logon |
| file (*proven*) | `staffix-messages-store-file` | `stores.messages.file.FileMessageStoreSettings` | `staffix.messages-stores-file.instances.<id>` | `storageDirectoryPath` required; `syncWrites` true by default |
| JDBC (*proven* by a scratch project) | `staffix-messages-store-jdbc` | `stores.messages.jdbc.JdbcMessageStoreSettings` | `staffix.messages-stores-jdbc.instances.<id>` | `dataSource` (Spring: `data-source-bean`); put it behind async; see [JDBC store](#jdbc-store) |
| async wrapper | `staffix-messages-store-async` | `stores.messages.async.AsyncMessagesStoreSettings` | `staffix.messages-stores-async.instances.<key>` with `wraps=<id>` | `asyncStoreSettings.asyncQueueDirectory` required (Spring: `async-queue-directory`); takes over the id it wraps; needs the Chronicle JVM flags |

### JDBC store

Worked out on a scratch copy of `plain-java-initiator`, PostgreSQL in prod and H2 in the test; no reference project
holds it. In plain Java, `fixMessagesStore(AsyncMessagesStoreSettings.builder()` with
`.asyncStoreSettings(AsyncStoreSettings.builder().asyncQueueDirectory(<data dir>/store-queue).build())` and
`.wrappedFixMessagesStoreSettings(JdbcMessageStoreSettings.builder().dataSource(dataSource).build())`.

- **Schema**: `staffix-jdbc-ddl-generator-maven-plugin` (`org.lolaf.staffix`, goal `generate-ddl`, `schema`
  `MESSAGES`), one execution per dialect: the prod one (`org.hibernate.dialect.PostgreSQLDialect`, ...) with
  `createDropStatements` false, since the script otherwise starts by dropping the tables, writing
  `target/generated-ddl/schema.sql` for the user to apply; an `H2Dialect` one with `outputDirectory`
  `target/generated-ddl/h2` for the test.
- **DataSource**: HikariCP with the JDBC URL, user and password from environment variables and a short
  `connectionTimeout` (1 s), the database driver at runtime scope. Plain Java: `ProdEnvironment` takes the
  `DataSource`, so the test hands it H2's.
- **The test** runs the H2 script once with `RUNSCRIPT FROM 'target/generated-ddl/h2/schema.sql'` on a
  `jdbc:h2:mem:<name>;DB_CLOSE_DELAY=-1` database, never through the URL's `INIT`, which reruns it on every
  connection and drops what the store wrote. After the round trip it awaits rows in `fix_messages`.

## Message loggers

Selected by a session's `fixMessageLoggerInstanceId`. With none registered, sessions are not logged; once any is, a
session naming an unknown id is refused. Doc: `docs/stores-and-loggers.md`.

| logger | artifact | Java settings | Spring prefix | notes |
| :- | :- | :- | :- | :- |
| SLF4J (*proven*) | `staffix-messages-logger-slf4j` | `stores.loggers.slf4j.Slf4jMessagesLoggerSettings` | `staffix.messages-loggers-slf4j.instances.<id>` | into the application's logging; `messageFieldsDelimiter('|')` for readable logs |
| file (*proven*) | `staffix-messages-logger-file` | `stores.loggers.file.FileMessagesLoggerSettings` | `staffix.messages-loggers-file.instances.<id>` | `logDirectory` |
| OTLP | `staffix-messages-logger-otlp` | `stores.loggers.otlp.OtlpMessagesLoggerSettings` | `staffix.messages-loggers-otlp.instances.<id>` | `otlpEndpointUrl`, from an environment variable |
| demux | `staffix-messages-logger-demux` (*full*, no Spring module) | `stores.loggers.demux.DemuxMessagesLoggerSettings` | | several loggers at once |
| async wrapper (*proven*) | `staffix-messages-logger-async` | `stores.loggers.async.AsyncMessagesLoggerSettings` | `staffix.messages-loggers-async.instances.<key>` with `wraps=<id>` | as the async store |

## Session settings stores

Where the sessions come from. Doc: `docs/session-settings-stores.md`, and `docs/configuring-sessions.md` for what a
session holds.

| store | artifact | Java settings | Spring prefix | notes |
| :- | :- | :- | :- | :- |
| memory (*proven* in Spring) | `staffix-sessions-settings-store-memory` | `stores.sessions.memory.MemorySessionsSettingsStoreSettings` | `staffix.sessions-settings-stores-memory.instances.<id>.sessions[n]` | the Spring default: sessions in properties |
| file (*proven* in Java) | `staffix-sessions-settings-store-file` | `stores.sessions.file.FileSessionsSettingsStoreSettings` | `staffix.sessions-settings-stores-file.instances.<id>` | YAML, `${env:...}` placeholders; a directory store writes runtime changes back, a URI list does not; no `classpath:` URIs |
| JDBC | `staffix-sessions-settings-store-jdbc` | `stores.sessions.jdbc.JdbcSessionsSettingsStoreSettings` | `staffix.sessions-settings-stores-jdbc.instances.<id>` | sessions shared by engines; fixed engine and store instance ids |

## Monitoring and tracing (session plugins)

Registered with `fixSessionsPlugin(...)` and named per session in `fixSessionPluginsInstanceIds`, keyed by plugin
class: `org.lolaf.staffix.api.monitoring.FixSessionsMonitoringManager` for metrics,
`org.lolaf.staffix.tracing.otlp.OtelTracing` for tracing. In a session file that is a map under
`fixSessionPluginsInstanceIds`, the class name as key; in Spring properties
`...sessions[0].fix-session-plugins-instance-ids.[org.lolaf.staffix.api.monitoring.FixSessionsMonitoringManager]=<id>`.
Unlike stores and loggers, a session naming no plugin gets none: there is no `default` plugin. A session naming a
plugin the engine does not register runs without it only when the engine registers no plugin at all, so dev can share
the session with prod; once any is registered, an unknown id fails at start.
Doc: `docs/monitoring.md`.

| plugin | artifact | Java settings | Spring prefix | notes |
| :- | :- | :- | :- | :- |
| Micrometer, OTLP export (*proven* by a generated project, no reference) | `staffix-monitoring-micrometer-otlp` | `monitoring.micrometer.otlp.OtlpMicrometerMonitoringManagerSettings` | `staffix.monitoring.otlp.instances.<id>` | `otlpEndpointUrl`; set `baseTimeUnit` to microseconds |
| Micrometer, own registry | `staffix-monitoring-micrometer` | `monitoring.micrometer.MicrometerMonitoringManagerSettings` | `staffix.monitoring.instances.<id>`, `staffix.monitoring.meter-registry-bean` | e.g. a Prometheus registry the application exposes |
| OpenTelemetry tracing | `staffix-monitoring-tracing-otlp` | `tracing.otlp.OtelTracingSettings` | `staffix.tracing.otel.instances.<id>` | needs a sender beside it, or it fails at start ("No HttpSenderProvider found"): `staffix-monitoring-tracing-otlp-sender` (*full*, HTTP) or `-sender-grpc`; W3C propagation needs `allowUserDefinedFields` in the session's validation settings |
| async wrapper | `staffix-monitoring-async-plugin` | `plugins.async.AsyncFixSessionsPluginSettings` | `staffix.async-plugin.instances.<id>.wraps` | keeps monitoring off the message path; drops samples when full; an in-memory queue, no Chronicle |
| throttling wrapper | `staffix-monitoring-throttling-plugin` | `plugins.throttling.ThrottlingFixSessionsPluginSettings` | `staffix.throttling-plugin.instances.<id>.wraps` | samples per-message callbacks |

## Alerting

Selected by a session's `fixAlertsPublisherInstanceId`. With none registered, alerts go nowhere; once any is, a session
naming an unknown id, `default` included, fails at start. Doc: `docs/alerting.md`.

| publisher | artifact | Java settings | Spring prefix | notes |
| :- | :- | :- | :- | :- |
| SLF4J | `staffix-alerts-publisher-slf4j` | `alerting.slf4j.Slf4jAlertsPublisherSettings` | `staffix.alerts-publishers-slf4j.instances.<id>` | alerts into the application's logs |
| Prometheus Alertmanager | `staffix-alerts-publisher-alertmanager` | `alerting.alertmanager.AlertmanagerAlertsPublisherSettings` | `staffix.alerts-publishers-alertmanager.instances.<id>` | `alertmanagerUrls` (Spring: `urls`) |

## Administration

One exporter per engine (`adminApiExporter`). Doc: `docs/runtime-administration.md`.

| exporter | artifact | Java settings | Spring prefix | notes |
| :- | :- | :- | :- | :- |
| JMX | `staffix-admin-api-jmx` | `admin.jmx.JmxAdminApiSettings` | `staffix.admin-api-jmx` | `jmxDomain` |
| HTTP, for the staffix-admin console | `staffix-admin-api-http` | `admin.http.HttpAdminApiSettings` | `staffix.admin-api-http` | `apiToken` random by default, known only to the console it is announced to; TLS through an SSL context (Spring: `ssl-bundle`); with Spring it serves in every profile once on the classpath: exclude `org.lolaf.staffix.admin.http.spring.HttpAdminApiAutoConfiguration` (`spring.autoconfigure.exclude`) where it must not |
| Spring Boot actuator | `staffix-spring-boot-actuator` (*full*) | | `staffix.actuator` | `fix-sessions` endpoint; read the doc before folding session state into health |

Java settings classes are under `org.lolaf.staffix.`.
