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
- High availability: no reference project; the staffix repository's `examples/high-availability` runs a pair.

## The doc to read for each kind

| kind | doc |
| :- | :- |
| `messages-store`, `messages-logger` | `docs/stores-and-loggers.md` |
| `sessions-settings-store` | `docs/session-settings-stores.md`, and `docs/configuring-sessions.md` for what a session holds |
| `session-plugin` | `docs/monitoring.md`, `docs/session-plugins.md` |
| `alerts-publisher` | `docs/alerting.md` |
| `admin-api-exporter` | `docs/runtime-administration.md` |
| `high-availability` | `docs/high-availability.md`, and its section in `docs/spring-boot.md` |

## Naming a session plugin

A session names its plugins in `fixSessionPluginsInstanceIds`, a map keyed by the plugin class (the schema's
`x-staffix.pluginClasses`): in a session file, the class name as key under `fixSessionPluginsInstanceIds`; in Spring
Boot properties, `...sessions[0].fix-session-plugins-instance-ids.[org.lolaf.staffix.api.monitoring.FixSessionsMonitoringManager]=<id>`.

## High availability

The catalogue holds `ReplicationHaSettings`, not its leader election: `docs/high-availability.md`, "Setting it up",
gives the primos settings.

- **Election artifacts**: plain Java, `staffix-ha-primos` beside the component's `java.artifact`; Spring Boot,
  `staffix-ha-primos-spring-boot` and the primos starter beside its `spring.artifact`, at the project's staffix
  version. Take the primos version from the staffix version's root pom (`primos.version`).
- **Three voters**: the two nodes and a witness, on a third machine, run from `primos-witness-cli`. It never leads; it
  gives the node left after a loss its majority. Losing any two of the three stops the sessions.
- **Per node, from environment variables**: `nodeId`, the primos node id, its durable `stateDirectory`, its own
  storage. The `peers`, the cluster secret, the sessions and their settings are the same on both nodes.
- **A session is replicated only if it asks**: `highAvailability.replicationMode` in a session file,
  `...sessions[0].ha.replication-mode` in Spring Boot properties, the same on both nodes. A mismatch is refused.
  `ASYNC` unless the user cannot lose a message at a failover; the modes and what each loses are in
  `docs/high-availability.md`, "Replication modes".
- **Two nodes never share a store**: same tables or directory, the session is refused. A memory store on both nodes
  loses everything once both stop.
- **Application state is not replicated**: what the application keeps itself, such as open requests, is its own to
  rebuild on the node that takes over.
- **A follower does not run its sessions**: no acceptor bound, no initiator connecting. Counterparties of an acceptor
  pair need both addresses or a virtual address in front.
- **Proving it**: a test starts both nodes and a witness on local ports, waits for a leader, runs the round trip,
  stops the leader, and runs it again against the other node.

## Facts about a whole kind

- **Replacing a messages store loses its sequence numbers and messages**: the new store starts empty, so the sessions
  start again at 1 unless the counterparty resets too. Switch at a sequence reset agreed with the counterparty.
- **An environment that drops a component the sessions name** keeps one under that id, or the sessions it shares with
  other environments stop naming it: the index's `selectedBy` says which kinds refuse an unknown id.
