# Rules for the generated project

Each rule cost a failed build or a broken start in the reference projects. Follow them as written.

## Build

- **A standalone Maven project**: no staffix parent (there is no staffix BOM), a `staffix.version` property set to
  the staffix release this plugin targets, `0.9.1`.
- **Java 11** in plain Java, **Java 17** with Spring Boot (Spring Boot 4.1.1, the version staffix 0.9.1 is built
  with). Spring Boot comes in as an imported BOM, not as the parent, so the user can keep their own parent.
- **Lombok goes in the compiler's `annotationProcessorPaths`**, with an explicit `lombok.version` property: recent
  JDKs no longer run processors found on the classpath, and an imported BOM does not bring its version properties.
- **The JVM flags in `staffix.jvm.args`**, copied from a reference `pom.xml`, on surefire's `argLine`, on
  `spring-boot-maven-plugin`'s `jvmArguments`, and in the README's run command. Without them `@Contended` does not
  pad, staffix falls back to allocating paths, and Chronicle Queue, behind every async store and logger, fails to
  start. On JDK 26 and later Chronicle also asks for `--enable-final-field-mutation=ALL-UNNAMED`, a flag older JDKs
  refuse: it stays out of the defaults, and the warning is harmless.
- **With Spring Boot, declare `staffix-impl` and the `-spring-boot` modules**, never their `-impl`: staffix 0.9.1's
  starter does not bring the engine, and an impl declared again, above all in test scope, hides it from the
  autoconfiguration, which then skips the component without a word.

## Code

- **Generate first, then write the code**: run `mvn generate-sources` once the dictionary is set up, and write
  encoders and decoders against the generated classes. Enumerated fields take the generated `*Values` enums
  (`Side.SideValues.BUY`), timestamps a `UTCTime` and a `TimeUnit`.
- **Map only the fields the application reads**: an unmapped field is never parsed. Groups and the
  `ObjectInstanceStrategy` (`CACHED`, nested in `org.lolaf.staffix.api.codec.FixFieldsDecoderMapper`) are in
  `docs/decoding-messages.md`.
- **A group is encoded in separate statements**: `addNoXxx(count)` returns the group's encoder, which cannot return to
  the message, so set the message's fields, add the group and fill each entry, then send the message.
- **Encoders are reused** (`session.newEncoder(...).asReusable()`, `begin()` per message): one per message type per
  session, created in `setup` or the decoder's constructor, never per message.
- **A decoder is reused for the next message**: anything that outlives `onDecoded`, a listener included, copies the
  values during the call. A test reads them during the call too, not afterwards (a Mockito `argThat` is too late).
- **One application instance serves every session it is named by**; per-session state lives in what `setup` creates.
- **No secrets or endpoints in the code**: environment variables, read where the environment is built.

## Environments

- **dev**: memory store, SLF4J logger with `|` as field delimiter, sequence numbers reset on each logon (initiator).
  Nothing else.
- **prod**: the user's choice from `${CLAUDE_PLUGIN_ROOT}/references/components.md`, every file under one data
  directory (`STAFFIX_DATA_DIR`), every endpoint and secret from an environment variable.
- Plain Java: `StaffixEnvironment` with `DevEnvironment` and `ProdEnvironment`, picked by `STAFFIX_ENV`, as in
  `plain-java-initiator`. `ProdEnvironment` takes everything it needs in its constructor (the data directory, an
  OTLP endpoint, ...), and `StaffixEnvironment` reads it from environment variables, so a test can build it. Spring Boot: `application.properties` with `spring.profiles.default=dev`,
  plus `application-dev.properties` and `application-prod.properties`, as in `spring-boot-initiator`.
- Spring cannot override one entry of an indexed list from a profile file: a session setting that differs per profile
  goes through a property placeholder (`reset-seq-num-on-logon=${fix-client.reset-seq-num-on-logon}`).
- An async wrapper takes over the instance id it wraps, so sessions keep naming the wrapped component.

## Tests

- **The round trip in every environment**: the application against an in-process counterparty (a broker simulator
  for an initiator, a client simulator for an acceptor), once in dev and once in prod against a temporary data
  directory. Prod components are only proven by running them.
- **The counterparty's session gets its own name** (`broker-order-entry` beside `order-entry`): session ids are
  interned for the whole JVM, and staffix 0.9.1 mixes up the CompIDs of two sessions sharing a name there.
- Spring: one abstract test, a subclass per profile, `@DirtiesContext(AFTER_CLASS)` and a broker per class, so two
  cached contexts never log on to the same counterparty at once.
- An acceptor under test gets a free port per test class: plain Java passes it to the gateway, Spring sets
  `staffix.acceptors.<name>.bind-address` from a `@DynamicPropertySource`. A Spring acceptor needs `bind-address`,
  and with no `target-sessions-settings-store-instance-ids` it serves every settings store.
- An OTLP endpoint in a prod test can point at a closed port: the round trip passes, and the exporter logs a refused
  connection when the engine stops.
- **Finish with `mvn verify`** and fix what fails before handing the project over.
