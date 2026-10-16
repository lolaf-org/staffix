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
  start.
- **With Spring Boot, declare `staffix-impl` and the `-spring-boot` modules**, never their `-impl`: staffix 0.9.1's
  starter does not bring the engine, and an impl declared again, above all in test scope, hides it from the
  autoconfiguration, which then skips the component without a word.

## Code

- **Generate first, then write the code**: run `mvn generate-sources` once the dictionary is set up, and write
  encoders and decoders against the generated classes. Enumerated fields take the generated `*Values` enums
  (`Side.SideValues.BUY`), timestamps a `UTCTime` and a `TimeUnit`.
- **Map only the fields the application reads**: an unmapped field is never parsed.
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
- Plain Java: `StaffixEnvironment` with `DevEnvironment` and `ProdEnvironment(Path dataDirectory)`, picked by
  `STAFFIX_ENV`, as in `plain-java-initiator`. Spring Boot: `application.properties` with `spring.profiles.default=dev`,
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
- **Finish with `mvn verify`** and fix what fails before handing the project over.
