# Session settings stores

A settings store holds the `FixSessionSettings` of the sessions an engine runs. [Configuring a
session](configuring-sessions.md) covers what goes into those settings. This guide covers where they are kept, and how
they reach the engine.

Two stores ship with Staffix:

| store | module | settings come from | changes are written back |
|-------|--------|--------------------|--------------------------|
| memory | `staffix-sessions-settings-store-memory-impl` | Java, or Spring properties | no, kept in memory only |
| file | `staffix-sessions-settings-store-file-impl` | YAML files, in a directory or at URIs | to the directory, never to URIs |

---

## How the engine uses a store

A store is registered on the engine like every other component, under an instance id:

```java
FixEngineBuilder.builder()
        .fixSessionsSettingsStore(MemorySessionsSettingsStoreSettings.builder()
                .instanceId("ACCEPTOR")
                .fixSessionSetting(acceptorSession)
                .build())
        // …
```

**An acceptor serves every session of the stores it targets.** It names them with
`targetFixSessionsSettingsStoreInstancesId`; with none named, it uses the store whose id is `default`. Adding a
session to one of those stores makes the acceptor accept it, without a restart.

```java
fixEngine.newAcceptor(FixAcceptorBuilder.builder()
        .bindAddress(bindAddress)
        .targetFixSessionsSettingsStoreInstancesId("ACCEPTOR")
        .build()).start();
```

**An initiator owns one session**, named by its `fixSessionId`. The engine looks for an `INITIATOR` session with that id
in every registered store and takes the first match; if none has it, building the initiator fails and lists the ids it
did find.

A store is looked up by `FixSessionId` **and** `FixSessionType`, so one store may hold the acceptor and the initiator
side of the same id.

---

## The memory store

The sessions are declared in Java and held in memory:

```java
MemorySessionsSettingsStoreSettings.builder()
        .instanceId("ACCEPTOR")
        .fixSessionSetting(session1)
        .fixSessionSetting(session2)
        .defaultFixSessionSettings((declared, builder) -> builder.fixMessageStoreInstanceId("ACCEPTOR"))
        .build();
```

`defaultFixSessionSettings` is applied to every session, the declared ones and any added at runtime. It receives the
session as declared and a builder already loaded with it, so it can check a field before setting it. On a map field
the last write wins, so a default written there replaces the session's own entry unless the function checks first.

The store has no backing source: `add`, `update` and `remove` change what it holds, and nothing survives a restart. A
[reload](runtime-administration.md#reloading-settings) returns what it holds, so it changes nothing. It is the store
for tests, for sessions built from your own configuration system, and for Spring Boot, where
`staffix.sessions-settings-stores-memory.instances.<name>.sessions[n]` declares its sessions (see [Spring
Boot](spring-boot.md#sessions)).

---

## The file store

The sessions are YAML files, one session per file:

```java
FileSessionsSettingsStoreSettings.builder()
        .instanceId("ACCEPTOR")
        .fixSessionSettingsDirectory(new File("/etc/myapp/sessions/acceptor"))
        .build();
```

```yaml
# yaml-language-server: $schema=fix-session-settings.v1.schema.json
fixSessionId:
  id: "acceptor-initiator1"
  fixVersion: "FIX.4.4"
  senderCompID: "ACCEPTOR"
  targetCompID: "INITIATOR_1"
fixSessionType: "ACCEPTOR"
fixMessageStoreInstanceId: "ACCEPTOR"
resetSeqNumOnLogon: true
```

The [`file-session-settings`](../examples/file-session-settings) example runs an acceptor from a directory and its
initiators from the classpath, with placeholders in both.

### The JSON schema

The build generates a JSON schema from the YAML model, so an editor completes and validates a session file, including
`${...}` placeholders in fields that are not strings. It is the
[`fix-session-settings.v1.schema.json`](../sessions-settings-stores/file/file-impl/etc/fix-session-settings.v1.schema.json)
in the module's `etc` directory, and it can be reached in three other ways:

| where | how |
|-------|-----|
| in the jar | the resource `org/lolaf/staffix/stores/sessions/file/fix-session-settings.v1.schema.json` |
| in the Maven repository | `staffix-sessions-settings-store-file-impl`, classifier `schema`, type `json` |
| next to your files | a directory-backed store copies it into its directory on start |

A file opts in with a comment read by editors built on the YAML language server. The path is relative to the file,
or a URL:

```yaml
# yaml-language-server: $schema=fix-session-settings.v1.schema.json
```

### A directory

Every `*.yaml` file in the directory is a session, except `default.yaml`.

**`default.yaml` holds what the sessions share.** Each session file is merged over it: a field the session leaves out
is taken from the defaults, and nested groups such as `heartBeatInterval` are merged field by field. Lists are
concatenated, and maps are merged key by key, the session's own entry winning.

**Reading is strict.** An unknown field, an unknown enum value or trailing content fails the load and names the file,
so a typo is an error rather than a silently missing setting. The merged result is then validated.

**The directory is also where changes go.** `add` and `update` write the session's file, `remove` deletes it. On
start, the store creates the directory if needed and copies the [JSON schema](#the-json-schema) into it. A
read-only directory only costs the schema file, with a warning.

### Somewhere other than a directory

The store can instead be given a list of URIs: a network location, or a classpath resource the caller has resolved
itself:

```java
FileSessionsSettingsStoreSettings.builder()
        .fixSessionSettingsUri(URI.create("https://config.example.com/sessions/initiator1.yaml"))
        .fixSessionSettingsUri(MyApp.class.getResource("/sessions/default.yaml").toURI())
        .fixSessionSettingsUri(MyApp.class.getResource("/sessions/initiator1.yaml").toURI())
        .build();
```

Each URI is opened with `URI.toURL().openStream()`, so a resource inside a jar works as well as one on disk.

A URI list cannot be enumerated the way a directory can, so **each URI names one file**. One of them may end in
`default.yaml`, and it provides the defaults merged into the others; a second one is an error, as is the same session
id arriving from two URIs.

**A store takes a directory or a list of URIs, never both**; providing both, or neither, fails at construction. And
**settings read from a URI are never written back**: there is nowhere to write, so `add`, `update` and `remove` change
the settings in memory and skip the write with a log rather than failing.

### Externally configured values

A value in a session file may be a `${...}` placeholder resolved when the file is loaded, so one file can be deployed
unchanged across environments:

```yaml
fixSessionId:
  id: "${sysprop:session.id:defaultIfAbsent}"
  fixVersion: "FIX.4.4"
logInOrOutResponseTimeout: "${env:LOGON_TIMEOUT:PT30S}"
```

Three forms are accepted: `${sysprop:key:default}`, `${env:KEY:default}`, and `${key:default}`, the last matching
Spring's own syntax, asking every source in turn. The default is optional, may itself contain `:`, and is used only
after every resolver has declined; with no default, the load fails naming the placeholder and the field. A value may
be part placeholder, `prefix-${env:X}-suffix`, and may hold several. Nothing escapes an opening brace, so a value that
merely looks like a placeholder is treated as one.

Placeholders work in a field of **any** type, not only strings: resolution happens on the parsed file before it is
bound, so `"${env:LOGON_TIMEOUT:PT30S}"` above is a valid `Duration`. They work the same in a directory and at URIs.

**Writing a session back keeps the placeholder.** The store rebuilds the file from the runtime settings, so it
remembers the file as it was read and puts each placeholder back. A value backed by a placeholder is owned by its
source, so **changing one through the store is refused** rather than silently written as a literal. A placeholder in
`default.yaml` is resolved but not remembered: that file is only ever read, and a value it contributes is written into
a session file as the literal it resolved to.

To resolve from somewhere else, implement `ConfigValueResolver` and hand it to the store:

```java
FileSessionsSettingsStoreSettings.builder()
        .fixSessionSettingsDirectory(directory)
        .configValueResolver(myResolver)
        .build();
```

Resolvers are asked in order and the first non-empty answer wins. Supplying any **replaces** the built-in
system-property and environment resolvers rather than joining them, so a deployment can say exactly where
configuration comes from. `refresh()` is called once per load, for a resolver backed by something that changes. It is
not called once per file, so every file in a load sees the same snapshot.

### Under Spring Boot

The file store is declared under `staffix.sessions-settings-stores-file.instances.<name>`, with either `.directory` or
`.uris`, never both. `.config-value-resolver-beans` names extra `ConfigValueResolver` beans; they are asked after the
system-property and environment resolvers and before Spring's own `Environment`.

---

## Changing settings on a running engine

Both stores accept `add`, `update` and `remove` while the engine runs, and the engine's acceptors and initiators react
to them. `reloadFixSessionsSettingsStore(instanceId)` re-reads a store and applies the difference the same way, which
is how an edited YAML file reaches a running engine. A changed session may be restarted and a removed one
disconnected: [Runtime administration](runtime-administration.md#reloading-settings) covers the reload, and
[Configuring a session](configuring-sessions.md#when-settings-change-under-a-running-session) the settings that decide
how each session takes it.
