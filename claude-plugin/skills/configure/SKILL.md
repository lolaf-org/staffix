---
name: configure
description: Changes the components of an existing staffix FIX application, in plain Java or Spring Boot: switches a message store or logger (file, JDBC, OTLP...), adds monitoring, tracing, alerting or a runtime admin API, or adds an environment such as staging, and proves the change with a round trip test. Use when the user wants to change how a staffix application stores, logs, monitors or is administered, or to add an environment.
argument-hint: "[what to change]"
allowed-tools: Read(/${CLAUDE_PLUGIN_ROOT}/**) Grep(/${CLAUDE_PLUGIN_ROOT}/**) Glob(/${CLAUDE_PLUGIN_ROOT}/**) Bash(grep *)
---

# Configure a staffix application

The user wants: $ARGUMENTS

Under `${CLAUDE_PLUGIN_ROOT}`:

- `references/components.md`: every component an environment can use, with its artifact, settings and the doc to
  read before configuring it.
- `references/environment-rules.md`: rules for environments, JVM flags and the tests that prove them. **Read it
  before changing anything.**
- `docs/`: the staffix documentation. `skills/new-app/reference-projects/`: working projects showing each rule.

Sessions, counterparties and messages are out of scope: a new counterparty speaks its own FIX API, so it is a new
application, not a configuration change.

## 1. Inspect the project

Find, and tell the user in a few lines:

- **Wiring and version**: plain Java or Spring Boot, the staffix version and the staffix artifacts in the `pom.xml`.
  Use that version for every artifact added; upgrading staffix is the user's call.
- **Environments**: how one is chosen (`STAFFIX_ENV`, Spring profiles, or the project's own way) and what each
  registers. Plain Java: the `FixEngineBuilder` calls `fixMessagesStore`, `fixMessagesLogger`,
  `fixSessionsSettingsStore`, `fixSessionsPlugin`, `fixAlertsPublisher`, `adminApiExporter`. Spring: the `staffix.*`
  properties of each profile file.
- **Instance ids**: those each component registers under, and those the sessions name (`fixMessageStoreInstanceId`,
  `fixMessageLoggerInstanceId`, `fixAlertsPublisherInstanceId`, `fixSessionPluginsInstanceIds`; left out, `default`).
- **Tests** that start an environment and exchange messages, and the JVM flags of `environment-rules.md`.

## 2. Ask what to change

Ask with the AskUserQuestion tool, each question with a recommended default; skip what the request already answers.
Without the tool, take the answers from the request and the defaults, and list the defaults taken when handing over.

1. **Which environment**, prod unless said otherwise, or a new one and which existing one it starts from.
2. **Which component**, from `components.md`, and its settings. Read the doc its row names first, and offer the
   settings that doc recommends for production. Endpoints and secrets become environment variables.

## 3. Change it

- **Add the artifacts** as `components.md` says, and remove those nothing uses any more.
- **Keep the instance id the sessions already name** when replacing a component, so no session changes. A new
  instance id means every session that should use it names it, in every environment that shares the session.
- **A session naming an unregistered component**: a store, a logger, or an alerts publisher once any is registered,
  fails at start; a plugin fails at start once the engine registers any plugin. An environment that drops a
  component keeps one under the id the sessions name, or the sessions it shares with other environments stop
  naming it.
- **Replacing the prod message store loses its sequence numbers and messages**: the sessions start again at 1 unless
  the counterparty resets too. Tell the user to switch at a sequence reset agreed with the counterparty.
- **An async wrapper or Chronicle**: check the JVM flags are where `environment-rules.md` puts them, and add them
  if not.
- **A new environment**: plain Java, a `StaffixEnvironment` implementation and its case; Spring, an
  `application-<name>.properties`. Its data directory and endpoints come from environment variables like prod's.
- In a project not shaped like the reference projects, make the change where the project already selects its
  components, in its style, without restructuring it.

## 4. Prove it

1. Make the changed environment run its round trip: extend the test that starts it, or add one in the project's test
   style as `environment-rules.md` describes. A component is proven only by starting it.
2. Update the README: each new environment variable with its default, and the new environment's run command.
3. Run `mvn verify` (`./mvnw` when the project has it). Fix what fails, reading the docs rather than guessing, until
   it passes.

## 5. Hand over

Tell the user what changed in which environment, the new environment variables, the defaults taken, and what the
change needs in production: a database schema, a collector or Alertmanager to point at, a sequence reset.
