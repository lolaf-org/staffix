# Staffix components

What an environment can be made of. Every component is registered on the engine under an instance id and named by
the sessions that use it; an id left out is `default`. Read the doc in the last column before configuring one: it
holds the settings, their defaults and what each costs.

Artifacts are `org.lolaf.staffix:<artifact>-impl` in plain Java and `<artifact>-spring-boot` with Spring Boot, at the
staffix version; a name marked *full* is used as it is. With Spring Boot declare the `-spring-boot` artifact only: it
brings the `-impl`, and declaring the impl yourself (worse, in test scope) can hide it from the autoconfiguration.

Components marked *proven* run in a reference project; for the others, the generated prod test is the proof.

## Message stores

Selected by a session's `fixMessageStoreInstanceId`. Doc: `docs/stores-and-loggers.md`.

| store | artifact | Java settings | Spring prefix | notes |
| :- | :- | :- | :- | :- |
| memory (*proven*) | `staffix-messages-store-memory` | `stores.messages.memory.MemoryMessageStoreSettings` | `staffix.messages-stores-memory.instances.<id>` | nothing survives a restart: dev, tests, sessions resetting on logon |
| file (*proven*) | `staffix-messages-store-file` | `stores.messages.file.FileMessageStoreSettings` | `staffix.messages-stores-file.instances.<id>` | `storageDirectoryPath` required; `syncWrites` true by default |
| JDBC | `staffix-messages-store-jdbc` | `stores.messages.jdbc.JdbcMessageStoreSettings` | `staffix.messages-stores-jdbc.instances.<id>` | `dataSource` (Spring: `data-source-bean`); schema from `staffix-jdbc-ddl-generator-maven-plugin`; keep the pool timeout short; put it behind async |
| async wrapper | `staffix-messages-store-async` | `stores.messages.async.AsyncMessagesStoreSettings` | `staffix.messages-stores-async.instances.<key>` with `wraps=<id>` | `asyncStoreSettings.asyncQueueDirectory` required (Spring: `async-queue-directory`); takes over the id it wraps; needs the Chronicle JVM flags |

## Message loggers

Selected by a session's `fixMessageLoggerInstanceId`. Doc: `docs/stores-and-loggers.md`.

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
`org.lolaf.staffix.tracing.otlp.OtelTracing` for tracing. Doc: `docs/monitoring.md`.

| plugin | artifact | Java settings | Spring prefix | notes |
| :- | :- | :- | :- | :- |
| Micrometer, OTLP export | `staffix-monitoring-micrometer-otlp` | `monitoring.micrometer.otlp.OtlpMicrometerMonitoringManagerSettings` | `staffix.monitoring.otlp.instances.<id>` | `otlpEndpointUrl`; set `baseTimeUnit` to microseconds |
| Micrometer, own registry | `staffix-monitoring-micrometer` | `monitoring.micrometer.MicrometerMonitoringManagerSettings` | `staffix.monitoring.instances.<id>`, `staffix.monitoring.meter-registry-bean` | e.g. a Prometheus registry the application exposes |
| OpenTelemetry tracing | `staffix-monitoring-tracing-otlp` | `tracing.otlp.OtelTracingSettings` | `staffix.tracing.otel.instances.<id>` | W3C propagation needs `allowUserDefinedFields` in the session's validation settings |
| async wrapper | `staffix-monitoring-async-plugin` | `plugins.async.AsyncFixSessionsPluginSettings` | `staffix.async-plugin.instances.<id>.wraps` | keeps monitoring off the message path; drops samples when full |
| throttling wrapper | `staffix-monitoring-throttling-plugin` | `plugins.throttling.ThrottlingFixSessionsPluginSettings` | `staffix.throttling-plugin.instances.<id>.wraps` | samples per-message callbacks |

## Alerting

Selected by a session's `fixAlertsPublisherInstanceId`. Doc: `docs/alerting.md`.

| publisher | artifact | Java settings | Spring prefix | notes |
| :- | :- | :- | :- | :- |
| SLF4J | `staffix-alerts-publisher-slf4j` | `alerting.slf4j.Slf4jAlertsPublisherSettings` | `staffix.alerts-publishers-slf4j.instances.<id>` | alerts into the application's logs |
| Prometheus Alertmanager | `staffix-alerts-publisher-alertmanager` | `alerting.alertmanager.AlertmanagerAlertsPublisherSettings` | `staffix.alerts-publishers-alertmanager.instances.<id>` | `alertmanagerUrls` (Spring: `urls`) |

## Administration

One exporter per engine (`adminApiExporter`). Doc: `docs/runtime-administration.md`.

| exporter | artifact | Java settings | Spring prefix | notes |
| :- | :- | :- | :- | :- |
| JMX | `staffix-admin-api-jmx` | `admin.jmx.JmxAdminApiSettings` | `staffix.admin-api-jmx` | `jmxDomain` |
| HTTP, for the staffix-admin console | `staffix-admin-api-http` | `admin.http.HttpAdminApiSettings` | `staffix.admin-api-http` | `apiToken` from an environment variable; TLS through an SSL context (Spring: `ssl-bundle`) |
| Spring Boot actuator | `staffix-spring-boot-actuator` (*full*) | | `staffix.actuator` | `fix-sessions` endpoint; read the doc before folding session state into health |

Java settings classes are under `org.lolaf.staffix.`.
