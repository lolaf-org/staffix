---
name: new-app
description: Creates a new Java application on the staffix FIX engine, an initiator or an acceptor, in plain Java or Spring Boot, with its FIX dictionary cut to the messages it uses, dev and prod environments and a tested round trip. Use when the user wants to start a FIX client, gateway or server with staffix.
argument-hint: "[what the application does]"
allowed-tools: Read(/${CLAUDE_PLUGIN_ROOT}/**) Grep(/${CLAUDE_PLUGIN_ROOT}/**) Glob(/${CLAUDE_PLUGIN_ROOT}/**) Bash(grep *)
---

# Create a staffix application

The user wants: $ARGUMENTS

Everything this skill needs ships with it, under `${CLAUDE_PLUGIN_ROOT}`:

- `skills/new-app/reference-projects/`: three working projects to start from, each passing `mvn verify` against
  staffix 0.9.2-SNAPSHOT: `plain-java-initiator`, `plain-java-acceptor`, `spring-boot-initiator`.
- `skills/new-app/references/project-rules.md` and `references/environment-rules.md`: rules the generated project
  follows. **Read both before writing anything.**
- `skills/new-app/references/dictionary-setup.md` and `dictionary-messages.md`: the FIX dictionary and its messages.
- `references/catalogue.md`: how to read the components catalogue, bundled under `catalogue/` at the staffix
  version this plugin targets: every store, logger, monitoring, alerting and admin component, with its settings and
  guide. **Read it before choosing components.** `references/components.md`: what the catalogue cannot say.
- `docs/`: the staffix documentation, and `dictionaries/`: the dictionaries staffix ships.

## 1. Ask what to build

Ask with the AskUserQuestion tool, a few questions at a time, each with a recommended default. Skip what the user's
request already answers. Without the tool (a non-interactive run), take the answers from the request and the
defaults, and list the defaults taken when handing over.

1. **Role**: initiator (connects to a counterparty), acceptor (counterparties connect to it), or both in one engine.
2. **Wiring**: plain Java, or Spring Boot (Java 17).
3. **Dictionary**: from scratch (pick the FIX version, 4.4 by default), FIX 4.2 or 4.3, or a file the provider gave
   (ask for its path).
4. **Project**: directory, groupId, artifactId and base package, proposed from the request.
5. **Sessions**: for an initiator, host, port, SenderCompID and TargetCompID, and whether the counterparty wants
   credentials on the Logon; for an acceptor, the port and each counterparty's CompIDs. These become environment
   variables with dev defaults, never constants.
6. **Messages**: list the dictionary's application messages as `dictionary-messages.md` says, then ask which the
   application sends and which it receives.
7. **Prod environment**: offer presets, then let the user change any part of them:
   - *Standard*: file store, file logger behind the async logger. What the reference projects use.
   - *Observable*: Standard, plus Micrometer metrics over OTLP, SLF4J alerts and the JMX admin API.
   - *Database*: JDBC store behind the async store, file logger behind the async logger.
   Each choice is a component of the bundled catalogue's index: open its file, as `catalogue.md` says, before
   configuring it.

## 2. Build it

1. Start from the reference project matching role and wiring (both roles: the initiator project with an acceptor
   added from `plain-java-acceptor`; a Spring acceptor: `spring-boot-initiator` with a `staffix.acceptors.<name>`
   block, see `project-rules.md`). Read each of its files and write it into the project with the Write tool, renamed
   after what the application does (groupId, artifactId, package, classes): the plugin is outside the project, so a
   shell `cp` from it may need an approval the user is not there to give.
2. Set up the dictionary as `dictionary-setup.md` says for the user's case, with the chosen messages in
   `messages.txt`.
3. Run `mvn generate-sources`, then write the application against the generated classes: a decoder per received
   message mapping the fields it uses, an encoder per sent message, and the example order flow replaced by the
   user's. Keep the reference's structure: environments, session files or properties, simulator.
4. Build the prod environment from the chosen components: what each schema `required`, its guide's `production`
   settings, one of its `requiresOneOf`, and its `jvmFlags` where `environment-rules.md` says. Every path under
   `STAFFIX_DATA_DIR`, every endpoint or secret from an environment variable.
5. Adapt the tests: the counterparty simulator speaks the chosen messages, and the round trip runs in dev and in
   prod, proving each prod component as its guide's `proving` says.
6. Write a README: what the application does, how to run it (`mvn exec:exec` in plain Java, `mvn spring-boot:run`
   with Spring Boot, both already passing the JVM flags; `STAFFIX_ENV=prod` or `SPRING_PROFILES_ACTIVE=prod` for
   prod), every environment variable with its default, how to add a counterparty, and where the dictionary comes
   from.
7. Run `mvn verify`. When it fails, search the catalogue for the error message first: a guide's `failures` may
   give the fix. Otherwise read the docs rather than guess, until it passes.

## 3. Hand over

Tell the user what was generated, how to run it, which choices were made for them, the guide's `behaviour` of each
prod component, and what is theirs to fill in: the business logic behind each decoder, credentials, and production
endpoints.
