# Rules for staffix environments

What an environment is made of, and how it is proven. Each rule cost a failed build or a broken start in the
reference projects under `${CLAUDE_PLUGIN_ROOT}/skills/new-app/reference-projects/`. The components themselves are in
the catalogue (`catalogue.md`).

## Environments

- **dev**: memory store, SLF4J logger with `|` as field delimiter, sequence numbers reset on each logon (initiator).
  Nothing else.
- **prod**: components from the catalogue, every file under one data directory (`STAFFIX_DATA_DIR`), every
  endpoint and secret from an environment variable, never a constant.
- Plain Java: `StaffixEnvironment` with `DevEnvironment` and `ProdEnvironment`, picked by `STAFFIX_ENV`, as in
  `plain-java-initiator`. `ProdEnvironment` takes everything it needs in its constructor (the data directory, an
  OTLP endpoint, ...), and `StaffixEnvironment` reads it from environment variables, so a test can build it.
- Spring Boot: `application.properties` with `spring.profiles.default=dev`, plus `application-dev.properties` and
  `application-prod.properties`, as in `spring-boot-initiator`.
- Spring cannot override one entry of an indexed list from a profile file: a session setting that differs per profile
  goes through a property placeholder (`reset-seq-num-on-logon=${fix-client.reset-seq-num-on-logon}`).
- A project not built this way keeps its own structure: add to it the way it already selects its components.

## JVM flags

The flags in `staffix.jvm.args` of a reference `pom.xml` go on surefire's `argLine`, on `spring-boot-maven-plugin`'s
`jvmArguments`, on `exec-maven-plugin`, and in the README's run command. Without them `@Contended` does not pad,
staffix falls back to allocating paths, and Chronicle Queue, behind every async store and logger, fails to start. On
JDK 26 and later Chronicle also asks for `--enable-final-field-mutation=ALL-UNNAMED`, a flag older JDKs refuse: it
stays out of the defaults, and the warning is harmless.

## Proving an environment

- **The round trip in every environment**: the application against an in-process counterparty, once per
  environment, prod against a temporary data directory. A component is only proven by running it.
- **Check what a store or logger wrote**: a failing store or logger never stops the session, so the round trip passes
  without it. After the round trip, await its output: rows in its table, files in its directory.
- Spring: one abstract test, a subclass per profile, `@DirtiesContext(AFTER_CLASS)` and a counterparty per class, so
  two cached contexts never log on to the same counterparty at once.
- **Each component's guide `proving`** says what to await: rows, files, or a request on an in-process stand-in for
  a collector. A closed port only proves the application starts.
- **Finish with `mvn verify`** and fix what fails, reading the docs rather than guessing.
