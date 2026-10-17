---
name: configure
description: Changes the components of an existing staffix FIX application, in plain Java or Spring Boot: switches a message store or logger (file, JDBC, OTLP...), adds monitoring, tracing, alerting or a runtime admin API, or adds an environment such as staging, and proves the change with a round trip test. Use when the user wants to change how a staffix application stores, logs, monitors or is administered, or to add an environment.
argument-hint: "[what to change]"
allowed-tools: Read(/${CLAUDE_PLUGIN_ROOT}/**) Grep(/${CLAUDE_PLUGIN_ROOT}/**) Glob(/${CLAUDE_PLUGIN_ROOT}/**) Bash(grep *) Bash(mvn -q org.apache.maven.plugins:maven-dependency-plugin:3.8.1:unpack *) Bash(./mvnw -q org.apache.maven.plugins:maven-dependency-plugin:3.8.1:unpack *)
---

# Configure a staffix application

The user wants: $ARGUMENTS

Under `${CLAUDE_PLUGIN_ROOT}`:

- `references/catalogue.md`: how to get and read the components catalogue, the source of every fact about a
  component. **Read it first.**
- `references/components.md`: what the catalogue cannot say: dependency rules, the doc of each kind, facts about a
  whole kind.
- `references/environment-rules.md`: rules for environments, JVM flags and the tests that prove them. **Read it
  before changing anything.**
- `docs/`: the staffix documentation. `skills/new-app/reference-projects/`: working projects showing each rule.

Sessions, counterparties and messages are out of scope: a new counterparty speaks its own FIX API, so it is a new
application, not a configuration change.

## 1. Inspect the project

Find the staffix version, unpack the catalogue of that version as `catalogue.md` says, read its index, and tell the
user in a few lines:

- **Wiring and version**: plain Java or Spring Boot, the staffix version and the staffix artifacts in the `pom.xml`.
  Use that version for every artifact added; upgrading staffix is the user's call.
- **Environments**: how one is chosen (`STAFFIX_ENV`, Spring profiles, or the project's own way) and which components
  each registers: the index's `settingsClass` found in the plain Java code, its `spring.prefix` in a profile's
  properties.
- **Instance ids**: those each component registers under, and those the sessions, acceptors and initiators name, by
  each kind's `selectedBy` rules.
- **Tests** that start an environment and exchange messages, and the JVM flags of `environment-rules.md`.

## 2. Ask what to change

Ask with the AskUserQuestion tool, each question with a recommended default; skip what the request already answers.
Without the tool, take the answers from the request and the defaults, and list the defaults taken when handing over.

1. **Which environment**, prod unless said otherwise, or a new one and which existing one it starts from.
2. **Which component**, from the index's components of that kind, by their `summary`, then its settings: open its
   file, offer the guide's `production` settings and what the schema `required`, and read the doc of its kind in
   `components.md` for anything the guide leaves open. Endpoints and secrets become environment variables.

## 3. Change it

- **Add the artifacts** as `components.md` says, plus one of the component's `requiresOneOf`, and remove those nothing
  uses any more.
- **Respect the kind's `cardinality`**: a kind needing at least one is replaced, never only removed; a kind taking at
  most one is swapped, never doubled.
- **Keep the instance id the sessions already name** when replacing a component, so no session changes. A new
  instance id means everything that should use it names it, in every environment that shares the session, as the
  kind's `selectedBy` says. Tell the user what an unknown or missing name does there (`whenUnknown`, `whenUnnamed`).
- **A component that `wraps` another** takes over the wrapped instance id: register the wrapped one under another id.
- **`needsJvmFlags`**: put the file's `jvmFlags` where `environment-rules.md` says, if they are not there.
- **The facts about a whole kind** in `components.md`, such as a replaced store losing its sequence numbers: tell the
  user.
- **A new environment**: plain Java, a `StaffixEnvironment` implementation and its case; Spring, an
  `application-<name>.properties`. Its data directory and endpoints come from environment variables like prod's.
- In a project not shaped like the reference projects, make the change where the project already selects its
  components, in its style, without restructuring it.

## 4. Prove it

1. Make the changed environment run its round trip: extend the test that starts it, or add one in the project's test
   style as `environment-rules.md` describes, and follow the guide's `proving` for each component changed. A
   component is proven only by starting it.
2. Update the README, if the project has one: each new environment variable with its default, and the new
   environment's run command. Without one, give them in the hand-over instead.
3. Run `mvn verify` (`./mvnw` when the project has it). When it fails, search the catalogue for the error message
   first: a guide's `failures` may give the fix. Otherwise read the docs rather than guess, until it passes.

## 5. Hand over

Tell the user what changed in which environment, the new environment variables, the defaults taken, the guide's
`behaviour` of each component changed, and what the change needs in production: a database schema, a collector or
Alertmanager to point at, a sequence reset.
