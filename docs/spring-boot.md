# Spring Boot

The starter declares the whole engine in `application.properties`. Your Java is reduced to the one thing Spring
cannot write for you: the `FixApplication` beans that handle your business messages.

```xml
<dependency>
    <groupId>org.lolaf.staffix</groupId>
    <artifactId>staffix-spring-boot-starter</artifactId>
    <version>${staffix.version}</version>
</dependency>
```

Working example: [`spring-boot-starter-example`](../examples/spring-boot-starter-example).

---

## The shape of the configuration

Every pluggable part is a property namespace, and instances are keyed by the same **instance ids** used in Java;
see [Configuring a session](configuring-sessions.md#wiring-the-session-to-the-engine). The pattern is
`staffix.<part>.instances.<INSTANCE_ID>.<setting>`.

```properties
staffix.enabled=true

# the executor for sessions that have no connection, a bean of yours rather than the engine's own thread;
# it must be single threaded, see docs/threading-model.md
staffix.engine.disconnected-sessions-executor-bean=offlineSessionsExecutor

# stores and loggers, one instance each for the acceptor and the initiator
staffix.messages-stores-memory.instances.ACCEPTOR.max-entries-in-memory=1024
staffix.messages-stores-memory.instances.INITIATOR.max-entries-in-memory=1024
staffix.messages-loggers-slf4j.instances.ACCEPTOR.message-fields-delimiter=|
staffix.messages-loggers-slf4j.instances.INITIATOR.message-fields-delimiter=|
```

---

## Sessions

Sessions live in a settings store instance and are declared as an indexed list:

```properties
staffix.sessions-settings-stores-memory.instances.SHARED.sessions[0].type=acceptor
staffix.sessions-settings-stores-memory.instances.SHARED.sessions[0].fix-session-id.id=acceptor-session-1
staffix.sessions-settings-stores-memory.instances.SHARED.sessions[0].fix-session-id.fix-version=VERSION_44
staffix.sessions-settings-stores-memory.instances.SHARED.sessions[0].fix-session-id.sender-comp-id=ACCEPTOR
staffix.sessions-settings-stores-memory.instances.SHARED.sessions[0].fix-session-id.target-comp-id=INITIATOR_1
staffix.sessions-settings-stores-memory.instances.SHARED.sessions[0].fix-message-store-instance-id=ACCEPTOR
staffix.sessions-settings-stores-memory.instances.SHARED.sessions[0].fix-message-logger-instance-id=ACCEPTOR
staffix.sessions-settings-stores-memory.instances.SHARED.sessions[0].fix-application-instance-id=acceptorApp
staffix.sessions-settings-stores-memory.instances.SHARED.sessions[0].reset-seq-num-on-logon=true
```

Every setting from [Configuring a session](configuring-sessions.md) is available here in kebab-case, including
nested groups:

```properties
…sessions[1].heartbeat.initiator-interval=2s
```

**`fix-application-instance-id` is a Spring bean name.** That is the join between the two worlds:

```java
@Bean("acceptorApp")
public FixApplication acceptorApp() {
    return new MyAcceptorApplication();
}
```

---

## Acceptors and initiators

```properties
staffix.acceptors.primary.instance-id=ACCEPTOR
staffix.acceptors.primary.bind-address=localhost:17001
staffix.acceptors.primary.target-sessions-settings-store-instance-ids[0]=SHARED
staffix.acceptors.primary.io-workers.thread-groups[0].name=default
staffix.acceptors.primary.io-workers.thread-groups[0].io-thread-count=1
staffix.acceptors.primary.io-workers.thread-groups[0].select-strategy=WAKEUP
staffix.acceptors.primary.message-executor.executors-threads-count=1
staffix.acceptors.primary.message-executor.idle-strategy=WAIT_NOTIFY

staffix.initiators.primary.instance-id=INITIATOR_1
staffix.initiators.primary.fix-session-id.id=initiator-session-1
staffix.initiators.primary.fix-session-id.fix-version=VERSION_44
staffix.initiators.primary.fix-session-id.sender-comp-id=INITIATOR_1
staffix.initiators.primary.fix-session-id.target-comp-id=ACCEPTOR
staffix.initiators.primary.connect-addresses[0]=localhost:17001
staffix.initiators.primary.connection-retry=PT1S
```

`select-strategy` and `idle-strategy` are the latency knobs from
[Tuning for latency](tuning-for-latency.md#2-choose-where-the-cpu-goes), as enum names rather than constructed
objects: `WAKEUP` for the blocking selector, and the idle strategies by name. This is the one place where the
properties surface is narrower than the Java one: a custom `SelectStrategy` or `ThreadFactory` needs a bean or Java
configuration.

`scheduler-bean` names a bean to use as the scheduler, so the engine's timers share your application's executor
rather than creating their own.

---

## Monitoring and admin

```properties
staffix.admin-api-jmx.domain=com.example.trading

management.endpoints.web.exposure.include=fix-sessions,health,info
staffix.actuator.enabled=true
staffix.actuator.fix-session-state-contributes-to-heath-status=true
```

The actuator module adds a `fix-sessions` endpoint and, optionally, folds session state into the application's health
status; see [Runtime administration](runtime-administration.md#spring-boot).

Plugin wrappers compose by referencing another instance's key:

```properties
staffix.async-plugin.instances.md.wraps=md
staffix.async-plugin.instances.md.queue-size=2048
staffix.async-plugin.instances.md.consumer-thread-pool-size=1
staffix.async-plugin.instances.md.backpressure-policy=DROP
```

`wraps` takes the instance-id key of another sessions-plugin entry: a `staffix.monitoring-micrometer.instances.<key>`
or `staffix.tracing.otel.instances.<key>`. See [Monitoring](monitoring.md#keeping-monitoring-off-the-message-path).

Note the difference from the Java API here: in properties both plugins are declared and the wrapper *references* the
one it wraps by key, whereas in Java the wrapper *contains* the delegate's settings as `delegateSettings` and is
registered in its place. Same result, and the wrapper is transparent either way: sessions go on naming the delegate.

---

## Extending it

The starter is built on an SPI, so a component of your own can join the same configuration model rather than being
wired separately. `staffix-spring-boot-spi` defines the contribution points:

| contributor | contributes |
|-------------|-------------|
| `FixMessagesStoreSettingsContributor` | a message store |
| `FixMessagesLoggerSettingsContributor` | a message logger |
| `FixSessionsSettingsStoreSettingsContributor` | a session settings store |
| `FixSessionsPluginSettingsContributor` | a session plugin |
| `AdminApiExporterSettingsContributor` | an admin API exporter |
| `FixSessionSettingsPostProcessor` | a last pass over every session's settings before they are used |

That last one is the escape hatch: if a setting has no property yet, or you need to derive one at startup, a post
processor lets you reach every session's settings in Java without giving up properties for everything else.
