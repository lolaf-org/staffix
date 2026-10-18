# Staffix components: what the catalogue cannot say

Read `catalogue.md` first: the catalogue holds every component's artifacts, settings, defaults, requirements, how
sessions select it and its guide. This file holds only what is not in the code.

## Dependencies

- **Plain Java**: declare the component's `java.artifact`, at the project's staffix version.
- **Spring Boot**: declare the `spring.artifact` only. It brings the impl, and an impl declared again, above all in
  test scope, hides it from the autoconfiguration, which then skips the component without a word.

## What a reference project proves

A component proven here runs in a reference project under `skills/new-app/reference-projects/`, or ran in a project
built while writing this plugin; for the others, the generated environment test is the proof.

- Messages stores: memory and file (reference projects), JDBC behind async (a scratch copy of
  `plain-java-initiator`, PostgreSQL in prod and H2 in the test).
- Messages loggers: SLF4J, file, async (reference projects).
- Sessions settings stores: memory with Spring Boot, file in plain Java (reference projects).
- Micrometer over OTLP (a generated project).

## The doc to read for each kind

| kind | doc |
| :- | :- |
| `messages-store`, `messages-logger` | `docs/stores-and-loggers.md` |
| `sessions-settings-store` | `docs/session-settings-stores.md`, and `docs/configuring-sessions.md` for what a session holds |
| `session-plugin` | `docs/monitoring.md`, `docs/session-plugins.md` |
| `alerts-publisher` | `docs/alerting.md` |
| `admin-api-exporter` | `docs/runtime-administration.md` |

## Naming a session plugin

A session names its plugins in `fixSessionPluginsInstanceIds`, a map keyed by the plugin class (the schema's
`x-staffix.pluginClasses`): in a session file, the class name as key under `fixSessionPluginsInstanceIds`; in Spring
Boot properties, `...sessions[0].fix-session-plugins-instance-ids.[org.lolaf.staffix.api.monitoring.FixSessionsMonitoringManager]=<id>`.

## Facts about a whole kind

- **Replacing a messages store loses its sequence numbers and messages**: the new store starts empty, so the sessions
  start again at 1 unless the counterparty resets too. Switch at a sequence reset agreed with the counterparty.
- **An environment that drops a component the sessions name** keeps one under that id, or the sessions it shares with
  other environments stop naming it: the index's `selectedBy` says which kinds refuse an unknown id.
