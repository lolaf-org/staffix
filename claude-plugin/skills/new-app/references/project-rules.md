# Rules for the generated project

Each rule cost a failed build or a broken start in the reference projects. Follow them as written, with
`${CLAUDE_PLUGIN_ROOT}/references/environment-rules.md` for the environments, the JVM flags and the tests that prove
them.

## Build

- **A standalone Maven project**: no staffix parent (there is no staffix BOM), a `staffix.version` property set to
  `version` in the bundled catalogue's index (`catalogue.md`), never to the reference project's or one from memory.
  The project's own coordinates are the user's (`0.1.0-SNAPSHOT` unless they say), not the reference project's.
- **Java 11** in plain Java, **Java 17** with Spring Boot, at the `spring-boot.version` of the `spring-boot-initiator`
  reference project (the version staffix is built with). Spring Boot comes in as an imported BOM, not as the parent,
  so the user can keep their own parent.
- **Lombok goes in the compiler's `annotationProcessorPaths`**, with an explicit `lombok.version` property: recent
  JDKs no longer run processors found on the classpath, and an imported BOM does not bring its version properties.
- **Dependencies** as `${CLAUDE_PLUGIN_ROOT}/references/components.md` says, with Spring Boot above all.

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

## Tests

- **The counterparty is a simulator in the test**: a broker simulator for an initiator, a client simulator for an
  acceptor.
- An acceptor under test gets a free port per test class: plain Java passes it to the gateway, Spring sets
  `staffix.acceptors.<name>.bind-address` from a `@DynamicPropertySource`. A Spring acceptor needs `bind-address`;
  which settings stores it reads is the catalogue's `sessions-settings-store` rule for `ACCEPTOR`.
