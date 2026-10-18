# The staffix components catalogue

Every staffix release publishes `org.lolaf.staffix:staffix-components-catalogue`, generated from the code of each
component: what it is for, its settings with their defaults, what it requires, and how sessions select it. Take the
facts about a component from here, never from memory or from the prose docs, and never from staffix sources or jars:
the catalogue matches the version the project runs.

## Getting it

**For an existing Maven project**, unpack the catalogue of the project's own staffix version (its `staffix.version`
property, or the version of its staffix dependencies), from the project's directory, with `./mvnw` when the project
has it:

```
mvn -q org.apache.maven.plugins:maven-dependency-plugin:3.8.1:unpack \
  -Dartifact=org.lolaf.staffix:staffix-components-catalogue:<staffix version> \
  -DoutputDirectory=target/staffix-catalogue
```

The project's Maven resolves it, through the user's own repositories and settings. `mvn clean` deletes it: unpack it
again when `target/staffix-catalogue` is missing.

**For a new project, or a project Maven cannot build**, read the copy bundled with this plugin,
`${CLAUDE_PLUGIN_ROOT}/catalogue/`. Its index's `version` is the staffix release this plugin was built with: a new
project depends on that version, and the user is told which it is.

Below, `<catalogue>` is `target/staffix-catalogue/META-INF/staffix` or the bundled copy.

## Read the index first

`<catalogue>/catalogue.v1.json` is small: read it whole before choosing anything.

**`kinds`**, one entry per kind of component (`messages-store`, `messages-logger`, `sessions-settings-store`,
`session-plugin`, `alerts-publisher`, `admin-api-exporter`, `application-factory`):

- `cardinality`: how many the engine takes. `AT_LEAST_ONE`: the engine refuses to start without one, so a component
  is replaced, never only removed. `AT_MOST_ONE`: adding a second fails; with Spring Boot, two such modules on the
  classpath fail at start. `ANY`: none is fine.
- `selectedBy`: how an instance is picked by its instance id, one rule per part of the engine that picks it.
  - `by`: `SESSION` (the session settings name it), `ACCEPTOR` or `INITIATOR` (the acceptor's or initiator's own
    settings).
  - `setting`: the setting naming the instance, as the Java builder and the session files write it. Spring Boot
    writes a session setting in kebab case; `springSetting`, when present, is the full Spring property instead.
    No `setting`: nothing names an instance.
  - `whenUnnamed`: what is picked when nothing is named. `DEFAULT_INSTANCE`: the instance registered under
    `default`. `NONE`: none at all. `ALL`: every instance is searched in turn, the first holding the session wins.
  - `whenUnknown`: what a name the engine does not register does. `REFUSED`: the session, acceptor or initiator is
    refused when created. `REFUSED_ONCE_ANY_REGISTERED`: it runs without one while none of the kind is registered,
    and is refused once any is.

**`components`**, one entry per component:

- `settingsClass`: the Java settings class. Find it in a plain Java project's code to know the project uses it.
- `kind` and `summary`: what it is, and when to pick it over the others of its kind.
- `wraps`: it decorates another component of that kind and takes over its instance id.
- `requiresOneOf`: artifacts it cannot start without: add one of them beside it.
- `needsJvmFlags`: it needs extra JVM flags, listed in its file.
- `java`: its `artifact` and `file`.
- `spring`, absent when it has no Spring Boot module: its `artifact`, its property `prefix`, `instances` (the prefix
  of one instance, `<id>` standing for the instance id; absent for a single component) and its `file`. Find the
  prefix in a Spring project's properties to know the project uses it.

## Open only the files you need

Once the components to add or change are chosen, read their `file`, relative to `<catalogue>`, and no other:
`java.file` in plain Java, `spring.file` with Spring Boot.

**`components/<settings class>.v1.schema.json`** is a JSON Schema of the settings as the builder sees them, one
property per builder method:

- `required` lists what must be set; `default` is the value when left out.
- `x-staffix-codeDefault`: the default is code, such as an executor the component creates.
- `x-staffix-javaType`: the setting is Java code (a `DataSource`, a callback), set in code, never in a file.
- `x-staffix-kind`: the setting holds another component's settings, of that kind.
- `x-staffix` holds what is not one property: `kind`, `artifact`, `cardinality` and `selectedBy` (as in the index),
  `instanceIdFrom` (the wrapped setting whose instance id it takes over), `jvmFlags` (the literal flags),
  `requiresOneOf`, `pluginClasses` (for a session plugin, the class a session's `fixSessionPluginsInstanceIds`
  keys it by), and the `guide`.

**`spring/<settings class>.v1.json`** is the Spring Boot side: `artifact`, `prefix`, `instances`, `properties` (a
JSON Schema of the properties as written, in kebab case), a `guide` for what is particular to Spring, and
`component`, the whole schema above, so a Spring project reads one file.

## Use the guides

A guide, in the schema's `x-staffix.guide` and the Spring file's `guide`, says what the settings cannot:

- `summary`: what it is and when to pick it.
- `production`: how to set it up for production. Offer these settings when configuring prod.
- `proving`: how a test proves it works. Follow it when writing the environment's round trip test.
- `failures`: each `symptom`, what the user sees, with its `fix`. When a build, a start or a test fails, search the
  whole `<catalogue>` for a distinctive part of the error message: the guide quoting it gives the fix.
- `behaviour`: what it does at run time that the user must know. Pass it on when handing over.
