# FIX versions and dictionaries

Staffix does not parse FIX generically. For each FIX version it generates a package of type-safe encoders, decoders,
field classes and registries from a dictionary, at build time. This guide covers choosing a version, running more
than one, and generating a package from a dictionary of your own.

---

## The packages

| dependency | speaks | generated package |
|------------|--------|-------------------|
| `staffix-fix-42` | FIX 4.2 | `org.lolaf.staffix.fix42` |
| `staffix-fix-43` | FIX 4.3 | `org.lolaf.staffix.fix43` |
| `staffix-fix-44` | FIX 4.4 | `org.lolaf.staffix.fix44` |
| `staffix-fix-50` | FIX 5.0 | `org.lolaf.staffix.fix50` |
| `staffix-fix-50sp1` | FIX 5.0 SP1 | `org.lolaf.staffix.fix50sp1` |
| `staffix-fix-50sp2` | FIX 5.0 SP2 | `org.lolaf.staffix.fix50sp2` |
| `staffix-fix-latest` | FIX Latest | `org.lolaf.staffix.fixlatest` |
| `staffix-fixt-11` | FIXT.1.1 session layer | `org.lolaf.staffix.fixt11` |

Add the one you speak, and name it on the session id:

```java
FixSessionId.of(FixRegularVersion.VERSION_44, FixSessionId.FixSessionIdBuilder.builder()
        .id("initiator-session")
        .senderCompID("initiator")
        .targetCompID("acceptor")
        .build());
```

`FixRegularVersion` has `VERSION_42`, `VERSION_43`, `VERSION_44`, `VERSION_50`, `VERSION_50_SP1`, `VERSION_50_SP2`
and `VERSION_LATEST`.

**From FIX 5.0 onwards you also need `staffix-fixt-11`.** The session layer moved to FIXT.1.1 in FIX 5.0: the header,
the trailer and the session messages belong to the transport dictionary, and the application dictionary carries only
business messages. A FIX 5.0+ application dictionary has an empty `<header>` for exactly this reason.

---

## What a package gives you

For a message like QuoteRequest, in `org.lolaf.staffix.fix44`:

- **`encoders.QuoteRequestEncoder`** — a fluent, type-safe builder. `setQuoteReqID(String)` exists; a misspelling is
  a compile error, not a runtime reject.
- **`fields.QuoteReqID`, `fields.Symbol`, …** — one class per field, carrying its tag, type and location.
- **`msg.MessageTypes`** — the message-type constants, `MessageTypes.QuoteRequest`.
- **enum classes** for fields the dictionary enumerates — `Side.SideValues.BUY` rather than `'1'`.
- **registries**, published through the ServiceLoader SPI, so the engine finds the right field and message-type
  metadata for the version a session speaks.

Encoders come from the session so they are bound to it:

```java
QuoteRequestEncoder encoder = session.newEncoder(QuoteRequestEncoder.class).asReusable();
```

Decoding is the mirror image: implement `FixMessageDecoder`, declare the message type, map the fields you care about
onto setters. Fields you do not map are never parsed — see
[Tuning for latency](tuning-for-latency.md#5-map-only-the-fields-you-use).

---

## Deprecation is carried into the API

Staffix generates its dictionaries from the FIX Trading Community's Orchestra repository, and carries the standard's
deprecations through into `@Deprecated` on the generated field classes, setters, groups, enum constants and message
types.

The practical effect: **your compiler warns you when you use something the standard has retired.** A hand-maintained
dictionary carries deprecated elements with nothing to distinguish them, so nothing tells you.

---

## Running more than one version in one engine

Nothing stops an engine hosting FIX 4.2 and FIX 4.4 sessions at once — add both packages, and give each session the
matching `FixSessionId`. The registries are per-version and resolved through the SPI, so the right metadata follows
the session.

What each session must not share is its *instance ids* if it needs its own store, logger or application — see
[Configuring a session](configuring-sessions.md#wiring-the-session-to-the-engine).

---

## FIX Latest

`staffix-fix-latest` ships **93 application messages** — every application message that existed in FIX 4.4, described
as FIX Latest describes it today.

That is a deliberate cut. FIX Latest defines 173 messages, and generating encoders for all of them costs some 35,000
classes and around 400 MB of `target/classes`. The message list is a checked-in text file, and the whole standard is
one flag away:

```bash
mvn -Pfull-fix-latest install -pl fix-packages/fix-latest
```

To change which messages are included, edit
[`fix-latest-messages.txt`](../fix-packages/fix-latest/src/main/dictionaries/fix-latest-messages.txt) and regenerate
the dictionary beside it:

```bash
mvn -Porchestra-dictionary initialize -pl fix-packages/fix-latest
```

---

## Where the dictionaries come from

Every `fix-*` module keeps its dictionary in its own `src/main/dictionaries/`, checked into the repository, and
generates from that file at build time. The dictionary itself is regenerated on demand, never during a normal build:

```bash
mvn -Porchestra-dictionary initialize -pl fix-packages/fix-44
```

That cuts the Orchestra repository to a version **and an extension pack** — `FIX50SP2.xml` is 5.0SP2 as amended
through EP98, and the file says so on its root element. The cut is reproducible: the same orchestration plus the same
version and EP always produces the same dictionary.

Two versions cannot be generated this way and keep hand-maintained dictionaries instead: **FIX 4.2 and FIX 4.3**. An
Orchestra repository describes each message's *current* shape, and FIX 4.3 replaced the inline instrument fields with
the `Instrument` component — so cutting back to 4.2 removes that reference whole and produces messages missing
fields they should have. The pre-4.3 layouts are not in the file to recover.

---

## Generating a package from your own dictionary

Counterparty-specific dictionaries are common in FIX, and the encoders generator is a Maven plugin you can point at
any QuickFIX-format dictionary:

```xml
<plugin>
    <groupId>org.lolaf.staffix</groupId>
    <artifactId>staffix-fix-encoders-generator-maven-plugin</artifactId>
    <version>${staffix.version}</version>
    <configuration>
        <dictionaryFile>${project.basedir}/src/main/dictionaries/MYFIX44.xml</dictionaryFile>
        <sourcesOutputDirectory>${project.build.directory}/generated-sources/</sourcesOutputDirectory>
        <packageName>com.example.fix</packageName>
        <dictionaryId>counterparty-a</dictionaryId>
    </configuration>
    <executions>
        <execution>
            <phase>generate-sources</phase>
            <goals><goal>code-generator</goal></goals>
        </execution>
    </executions>
</plugin>
```

`dictionaryId` is how a session selects it — set `FixSessionSettings.dictionaryId("counterparty-a")` and that
session decodes with your dictionary while others keep the default. See the
[plugin's own README](../fix-packages/fix-encoders-generator-maven-plugin/README.md) for the full option list.

Two companion plugins are available for the same pipeline: the **dictionary sanitizer**, which strips fields nothing
references, and the **Orchestra dictionary generator**, if you would rather cut your dictionary from an orchestration
than maintain XML.
