---
name: migrate-from-quickfixj
description: Migrates a Java application from QuickFIX/J to the staffix FIX engine, in plain Java or Spring Boot: its Application callbacks, MessageCracker handlers, .cfg sessions, dictionaries, stores and logs, with a report of what has no staffix equivalent, a build, and a round trip against QuickFIX/J proving the wire is unchanged. Use when the user wants to replace QuickFIX/J with staffix.
argument-hint: "[what to migrate]"
allowed-tools: Read(/${CLAUDE_PLUGIN_ROOT}/**) Grep(/${CLAUDE_PLUGIN_ROOT}/**) Glob(/${CLAUDE_PLUGIN_ROOT}/**) Bash(grep *)
---

# Migrate from QuickFIX/J

The user wants: $ARGUMENTS

Under `${CLAUDE_PLUGIN_ROOT}/skills/migrate-from-quickfixj/references/`:

- `concepts.md`: each QuickFIX/J class and callback and what replaces it. **Read it first.**
- `cfg-keys.md`: each `.cfg` key, its staffix setting, its transform, and what to write when it is absent.

And under `${CLAUDE_PLUGIN_ROOT}`:

- `references/catalogue.md`: the components catalogue, for every store, logger and admin component. Use the bundled
  copy: the project has no staffix version yet. `references/components.md`: what the catalogue cannot say.
- `skills/new-app/references/project-rules.md` and `references/environment-rules.md`: rules a staffix project
  follows; `skills/new-app/references/dictionary-setup.md`: the dictionary and the generated messages.
- `skills/new-app/reference-projects/`: working staffix projects, the shape to aim for. `docs/`: the documentation.

A **no equivalent** in either reference is never dropped silently: it goes in the report, and stays in the code as a
`TODO(staffix)` comment if the user keeps it for later.

## 1. Inventory

Read the project without changing it, and list:

- **Build**: Maven or not, the Java version (staffix needs 11, 17 with Spring Boot), the QuickFIX/J artifacts and
  version, Spring Boot or plain Java (a QuickFIX/J Spring Boot starter counts).
- **Engines**: each `SocketInitiator`, `SocketAcceptor` and threaded variant, the `.cfg` it reads, the store and log
  factories it is given, and any JMX exporter.
- **Sessions**: every section of every `.cfg`, resolved with `[DEFAULT]` as `cfg-keys.md` says.
- **Dictionaries**: the `DataDictionary`, `AppDataDictionary` and `TransportDataDictionary` files, and whether they
  are QuickFIX/J's own or a counterparty's.
- **Application code**: each `Application` and `ApplicationExtended` callback with what it does, each `MessageCracker`
  handler, the message types sent and received, `Session.sendToTarget`, `lookupSession` and admin calls (`reset`,
  `setNextSenderMsgSeqNum`...), the exceptions thrown (`RejectLogon`, `DoNotSend`, `UnsupportedMessageType`,
  `FieldNotFound`...), and every other `quickfix.*` import.
- **Tests**: those that start an engine or exchange messages, and what plays the counterparty.

## 2. Report, and wait for approval

Write the migration plan as `STAFFIX-MIGRATION.md` at the project root, and show it to the user:

1. **What maps**, per area: concept, then each session's settings as the staffix setting and value they become.
2. **What changes behaviour**, from both references: the schedule's sequence resets, the acceptor's heartbeat bounds,
   the validation QuickFIX/J ran by default, a changed store starting from sequence number 1, the logging categories.
3. **No equivalent**: each one, where it is, and what comes closest.
4. **Choices**, each with a recommendation: plain Java or Spring Boot (keep the project's), the staffix store and
   logger for each QuickFIX/J one, whether to keep QuickFIX/J's validation checks or staffix's defaults, which side
   sends a non-stop day's reset, whether a daily reset is expected.

Ask with the AskUserQuestion tool. Change nothing until the user approves the report; record their answers in it.
Without the tool (a non-interactive run), take the recommendations, write them into the report as the defaults
taken, and go on.

## 3. Migrate

Follow the approved report, in this order, keeping the project's packages, names and style:

1. **Build**: remove the QuickFIX/J dependencies, add the staffix ones as `project-rules.md` says, plus the chosen
   components' artifacts as `components.md` says, and the encoders generator for the dictionary.
2. **Dictionary**: each dictionary file is kept unchanged as the project's dictionary, as `dictionary-setup.md`
   says for a provider dictionary; QuickFIX/J's own dictionaries become the shipped one of the same version. List in
   `messages.txt` the message types the inventory found, then run `mvn generate-sources`.
3. **Sessions**: a sessions settings store holding each session, converted key by key with `cfg-keys.md`: YAML
   files for the file store in plain Java, properties with Spring Boot. Endpoints, CompIDs and secrets become
   environment variables with the `.cfg`'s values as dev defaults. Delete the `.cfg` once nothing reads it.
4. **Engine**: the initiators and acceptors `cfg-keys.md` gives, and the chosen components, wired as the reference
   projects are. Environments as `environment-rules.md` says: dev, and prod with the components the QuickFIX/J
   application used in production.
5. **Application**: each callback and handler moved as `concepts.md` says. A handler becomes a decoder whose
   `mapFieldsForDecoding` binds the fields the handler read; a send becomes an encoder built once per session. Keep the
   business logic as it is: only the engine's API changes.
6. **Tests**: the existing ones moved to staffix, and a round trip in each environment as `environment-rules.md`
   says.

## 4. Prove it

1. Run `mvn verify` (`./mvnw` when the project has it). When it fails, search the catalogue for the error message
   first: a guide's `failures` may give the fix. Otherwise read the docs rather than guess, until it passes.
2. **Interop**: add a test that runs the migrated application against a QuickFIX/J engine playing its counterparty,
   QuickFIX/J in `test` scope at the version the project used, with the session mirrored (CompIDs swapped, the same
   dictionary and `HeartBtInt`). It sends and receives each message type the application handles, and checks the
   fields arrive as sent. Run `mvn verify` again: the wire is unchanged when it passes.
3. Search the sources for `quickfix` (imports, `.cfg`, `quickfixj.` logging categories) and account for each hit
   left: the interop test, or a `TODO(staffix)` the report names.

## 5. Hand over

Update `STAFFIX-MIGRATION.md` with what was done and what is left, and tell the user: what changed, the new
environment variables, the defaults taken, each behaviour change and no equivalent, and what production needs
before the switch: the counterparty told of a sequence reset or the numbers set through the admin API, a database
schema, the logging configuration that named the QuickFIX/J categories.
