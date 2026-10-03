# FIX versions and dictionaries

Staffix does not parse FIX generically. For each FIX version you speak, a Maven plugin generates a package of
type-safe encoders, field classes and registries from a dictionary, into your own build. Staffix ships the
dictionaries; you generate the code. This guide covers choosing a version, generating its encoders, running more than
one, and bringing a dictionary of your own.

---

## The dictionaries

Each application-layer artifact holds one dictionary, as a resource at the root of the jar, and no code:

| artifact | speaks | dictionary resource |
|----------|--------|---------------------|
| `staffix-fix-42` | FIX 4.2 | `FIX42.xml` |
| `staffix-fix-43` | FIX 4.3 | `FIX43.xml` |
| `staffix-fix-44` | FIX 4.4 | `FIX44.xml` |
| `staffix-fix-50` | FIX 5.0 | `FIX50.xml` |
| `staffix-fix-50sp1` | FIX 5.0 SP1 | `FIX50SP1.xml` |
| `staffix-fix-50sp2` | FIX 5.0 SP2 | `FIX50SP2.xml` |
| `staffix-fix-latest` | FIX Latest | `fix-latest-minimal.xml` |

The FIXT.1.1 session layer is the exception: `staffix-fixt-11` ships its encoders and registries already generated,
in `org.lolaf.staffix.fixt11`, since every FIX 5.0+ session needs the same ones.

### Why the encoders are not shipped

- **Size.** Maven Central strictly limits artifact size, and a full FIX package is large: the complete FIX
  Latest alone generates some 20,000 classes.
- **You rarely need all of it.** Most applications use a handful of messages. A dictionary of your own, stripped of
  the messages you do not use, generates less code, builds faster and keeps your API to what you actually speak.
  See [Cutting a dictionary from an orchestration](#cutting-a-dictionary-from-an-orchestration) and
  [Generating a package from your own dictionary](#generating-a-package-from-your-own-dictionary).

---

## Generating the encoders

Add `staffix-fix-encoders-generator-maven-plugin` to your build, with the dictionary's artifact as a dependency **of
the plugin**, and point `dictionaryFile` at the resource with a `classpath:` prefix:

```xml
<plugin>
    <groupId>org.lolaf.staffix</groupId>
    <artifactId>staffix-fix-encoders-generator-maven-plugin</artifactId>
    <version>${staffix.version}</version>
    <configuration>
        <dictionaryFile>classpath:FIX44.xml</dictionaryFile>
        <sourcesOutputDirectory>${project.build.directory}/generated-sources/fix</sourcesOutputDirectory>
        <packageName>com.example.fix44</packageName>
    </configuration>
    <executions>
        <execution>
            <goals><goal>code-generator</goal></goals>
        </execution>
    </executions>
    <dependencies>
        <dependency>
            <groupId>org.lolaf.staffix</groupId>
            <artifactId>staffix-fix-44</artifactId>
            <version>${staffix.version}</version>
        </dependency>
    </dependencies>
</plugin>
```

The goal runs at `generate-sources` and adds the generated sources to the compilation, so `mvn compile` is enough.
The dictionary is only read at build time: the artifact is not a dependency of your project and does not reach your
runtime classpath.

- **`packageName`** is yours to choose. The examples in these guides use `org.lolaf.staffix.fix44`.
- **`dictionaryId`** names the dictionary to the engine. It defaults to `default`, which is what a session uses
  unless its settings name another, see [your own dictionary](#generating-a-package-from-your-own-dictionary).
- **`dictionaryFile`** also takes a file path, relative to the project, for a dictionary you keep yourself.
- **`testSources`** set to `true` generates into the test sources instead, for encoders only your tests use. Set
  `resourcesOutputDirectory` to `${project.build.testOutputDirectory}` with it, or the registrations the generator
  writes land in the main output.

**From FIX 5.0 onwards add `staffix-fixt-11` as well**, as an ordinary dependency of your project. The session
layer moved to FIXT.1.1 in FIX 5.0: the header, the trailer and the session messages belong to the transport
dictionary, and the application dictionary carries only business messages. A FIX 5.0+ application dictionary has an
empty `<header>` for exactly this reason.

```xml
<dependency>
    <groupId>org.lolaf.staffix</groupId>
    <artifactId>staffix-fixt-11</artifactId>
    <version>${staffix.version}</version>
</dependency>
```

Then name the version on the session id:

```java
FixSessionId.of(FixRegularVersion.VERSION_44, FixSessionId.FixSessionIdBuilder.builder()
        .name("initiator-session")
        .senderCompID("initiator")
        .targetCompID("acceptor")
        .build());
```

`FixRegularVersion` has `VERSION_42`, `VERSION_43`, `VERSION_44`, `VERSION_50`, `VERSION_50_SP1`, `VERSION_50_SP2`
and `VERSION_LATEST`.

---

## What a package gives you

For a message like QuoteRequest, in `org.lolaf.staffix.fix44`:

- **`encoders.QuoteRequestEncoder`**: a fluent, type-safe builder. `setQuoteReqID(String)` exists; a misspelling is
  a compile error, not a runtime reject.
- **`fields.QuoteReqID`, `fields.Symbol`, …**: one class per field, carrying its tag, type and location.
- **`msg.MessageTypes`**: the message-type constants, `MessageTypes.QuoteRequest`.
- **enum classes** for fields the dictionary enumerates, so `Side.SideValues.BUY` rather than `'1'`.
- **registries**, published through the ServiceLoader SPI, so the engine finds the right field and message-type
  metadata for the version a session speaks.

Encoders come from the session so they are bound to it:

```java
QuoteRequestEncoder encoder = session.newEncoder(QuoteRequestEncoder.class).asReusable();
```

Decoding is the mirror image: implement `FixMessageDecoder`, declare the message type, map the fields you care about
onto setters. Fields you do not map are never parsed, see
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

Nothing stops an engine hosting FIX 4.2 and FIX 4.4 sessions at once: generate both, one execution each with its own
`packageName`, and give each session the matching `FixSessionId`. The registries are per-version and resolved through the SPI, so the right metadata follows
the session.

What each session must not share is its *instance ids* if it needs its own store, logger or application, see
[Configuring a session](configuring-sessions.md#wiring-the-session-to-the-engine).

---

## FIX Latest

`staffix-fix-latest` holds **93 application messages**, every application message that existed in FIX 4.4, described
as FIX Latest describes it today.

That is a deliberate cut: FIX Latest defines far more messages than an application is ever likely to encode, and
generating all of them produces some 35,000 classes. The message list is a checked-in text file, shipped in the jar
beside the dictionary as `fix-latest-messages.txt`. To cut your own list, run the
[Orchestra dictionary generator](#cutting-a-dictionary-from-an-orchestration) with your file as
`includeMessagesFile`.

In this repository, the whole standard is one flag away, which is how a new extension pack is proven to generate:

```bash
mvn -Pfull-fix-latest compile -pl fix-packages/fix-latest
```

To change which messages the shipped dictionary includes, edit
[`fix-latest-messages.txt`](../fix-packages/fix-latest/src/main/resources/fix-latest-messages.txt) and regenerate
the dictionary beside it:

```bash
mvn -Porchestra-dictionary initialize -pl fix-packages/fix-latest
```

---

## Where the dictionaries come from

Every `fix-*` module keeps its dictionary in its own `src/main/resources/`, checked into the repository, and ships
that file. The dictionary is regenerated on demand, never during a normal build:

```bash
mvn -Porchestra-dictionary initialize -pl fix-packages/fix-44
```

That cuts the Orchestra repository to a version **and an extension pack**: `FIX50SP2.xml` is 5.0SP2 as amended
through EP98, and the file says so on its root element. The cut is reproducible: the same orchestration plus the same
version and EP always produces the same dictionary.

Two versions cannot be generated this way and keep hand-maintained dictionaries instead: **FIX 4.2 and FIX 4.3**. An
Orchestra repository describes each message's *current* shape, and FIX 4.3 replaced the inline instrument fields with
the `Instrument` component, so cutting back to 4.2 removes that reference whole and produces messages missing
fields they should have. The pre-4.3 layouts are not in the file to recover.

---

## Generating a package from your own dictionary

Counterparty-specific dictionaries are common in FIX, and the encoders generator takes any QuickFIX-format dictionary,
from a file in your project as well as from the classpath:

```xml
<plugin>
    <groupId>org.lolaf.staffix</groupId>
    <artifactId>staffix-fix-encoders-generator-maven-plugin</artifactId>
    <version>${staffix.version}</version>
    <configuration>
        <dictionaryFile>src/main/dictionaries/MYFIX44.xml</dictionaryFile>
        <sourcesOutputDirectory>${project.build.directory}/generated-sources/fix</sourcesOutputDirectory>
        <packageName>com.example.fix</packageName>
        <dictionaryId>counterparty-a</dictionaryId>
    </configuration>
    <executions>
        <execution>
            <goals><goal>code-generator</goal></goals>
        </execution>
    </executions>
</plugin>
```

`dictionaryId` is how a session selects it: set `FixSessionSettings.dictionaryId("counterparty-a")` and that
session decodes with your dictionary while others keep the default. See the
[plugin's own README](../fix-packages/fix-encoders-generator-maven-plugin/README.md) for the full option list.

Two companion plugins serve the same pipeline, and the rest of this guide covers them: the **Orchestra dictionary
generator**, if you would rather cut your dictionary from an orchestration than maintain XML, and the **dictionary
sanitizer**, which strips what nothing in a dictionary references.

---

## Cutting a dictionary from an orchestration

An Orchestra repository is the FIX Trading Community's machine-readable standard: every version and extension pack
at once, each element marked with the version that added, changed or deprecated it. Cutting a dictionary from it
states exactly which standard a package speaks, and is the only source of the deprecations that become `@Deprecated`.
Every shipped `fix-*` dictionary is produced this way, and the plugin is available for your own.

The plugin depends on `staffix-fix-orchestra`, which carries the latest published repository, and reads it unless told
otherwise. So a cut needs no orchestration of your own:

```xml
<plugin>
    <groupId>org.lolaf.staffix</groupId>
    <artifactId>staffix-fix-orchestra-dictionary-generator-maven-plugin</artifactId>
    <version>${staffix.version}</version>
    <configuration>
        <upToVersion>FIX.4.4</upToVersion>
        <upToExtensionPack>0</upToExtensionPack>
        <markDeprecated>true</markDeprecated>
        <outputFile>${project.build.directory}/generated-resources/MYFIX44.xml</outputFile>
    </configuration>
    <executions>
        <execution>
            <phase>initialize</phase>
            <goals><goal>generate</goal></goals>
        </execution>
    </executions>
</plugin>
```

To read another repository, set `orchestration` to a file path or a `classpath:` resource, either the XML or the zip
it is published as. The output is ready for the encoders generator, whose `dictionaryFile` takes the same path.

The two ceiling parameters are the cut, and together they name a standard precisely:

- **`upToVersion`** is the last FIX version to keep, such as `FIX.4.4` or `FIX.5.0SP2`. Left out, every version in the
  repository is kept. It also decides where the session layer goes: up to FIX 4.4 the standard header, trailer and
  session messages belong in this dictionary, and from FIX 5.0 they belong to FIXT.1.1 and are left out.
- **`upToExtensionPack`** is the last extension pack to keep. Left out, every extension pack of the kept versions is
  kept, which is what "FIX.5.0SP2" means on its own: that release as amended. Set it to `0` for the release exactly as
  published, which is the cut that reproduces the classic dictionaries.

Three more are worth knowing:

- **`markDeprecated`** writes `deprecated="true"` onto the elements the cut keeps that the standard has retired. Off
  by default, because it makes the file no longer byte-for-byte what a QuickFIX toolchain would have written. On is
  what makes the generated API carry `@Deprecated`, so it is what the shipped packages use.
- **`includeDeprecated`** keeps what was already deprecated at the cut, and is on by default: deprecated means "do not
  use this in new work", not withdrawn, and the field has to stay so that a peer still sending it can be decoded.
  Turning it off is the deliberate act of not generating encoders for what the standard tells you not to use.
- **`includeMessagesFile`** names a file, or a `classpath:` resource, listing the messages to keep, one per line by name (`NewOrderSingle`) or by
  msgType (`D`), with `#` for comments. Left out, every message of the cut is kept. It earns its place on a wide cut
  such as FIX Latest, where one message expands into hundreds of classes once its groups and components are expanded,
  and an entry naming a message the cut does not hold fails the build rather than being ignored.

Before cutting, ask the orchestration what it holds. The `versions` goal needs no project and lists every version
with its extension-pack range, its element counts, and the exact arguments that select it:

```bash
mvn org.lolaf.staffix:staffix-fix-orchestra-dictionary-generator-maven-plugin:versions \
    -DoutputFile=target/fix-versions.txt
```

```
version          EP range   elements       base  cut
FIX.4.4              1-38       1844       1281  upToVersion=FIX.4.4 [upToExtensionPack=0 for the base release]
FIX.5.0SP2         98-259       8033          2  upToVersion=FIX.5.0SP2 [upToExtensionPack=0 for the base release]
```

And `dryRun` on the `generate` goal reports what a cut would remove without writing anything.

What the kept messages no longer reference is still written out, so the sanitizer below is the natural next step,
which is exactly how [`fix-latest`](../fix-packages/fix-latest/pom.xml) is built.

---

## Sanitizing a dictionary

A published dictionary is a catalogue, not your traffic: the shipped FIX 4.4 one defines 1,071 fields, FIX Latest
5,704. Every field becomes a class and a registry entry consulted per message, and crowds your IDE's completion.

The sanitizer removes only what the dictionary itself never refers to: components no message uses, then fields
nothing references. It logs what it removed, and runs before generation in the same build:

```xml
<plugin>
    <groupId>org.lolaf.staffix</groupId>
    <artifactId>staffix-fix-dictionary-sanitizer-maven-plugin</artifactId>
    <version>${staffix.version}</version>
    <executions>
        <execution>
            <phase>generate-sources</phase>
            <goals><goal>sanitize</goal></goals>
            <configuration>
                <inputFile>${project.basedir}/src/main/dictionaries/MYFIX44.xml</inputFile>
                <outputFile>${project.build.directory}/dictionaries/MYFIX44-sanitized.xml</outputFile>
            </configuration>
        </execution>
    </executions>
</plugin>
```

`inputFile` also takes a `classpath:` resource, so a shipped dictionary can be sanitized without copying it into
your project: `classpath:FIX44.xml`, with `staffix-fix-44` among the plugin's dependencies.

Then point the encoders generator's `dictionaryFile` at the sanitized output rather than the original. Keeping the
output under `target/` says which file is the source and which is derived; writing it next to the input is fine too,
and is what [`benchmarks`](../benchmarks/pom.xml) does.

Two options exist because "unreferenced" is not always the same as "unused":

- **`keepFields`** lists field names to keep whatever happens. From FIX 5.0 the header and the trailer are empty,
  since the session layer moved to FIXT.1.1, yet a 5.0+ dictionary still defines BeginString, BodyLength, MsgType,
  MsgSeqNum, SenderCompID, TargetCompID, SendingTime, CheckSum and ApplVerID below them. Nothing references them
  there, so without this they are all removed and the generated package quietly loses field classes its 4.4 sibling
  has.
- **`sanitizeMsgTypeField`** prunes MsgType(35)'s enumerated values to the messages this dictionary defines. It
  defaults to true, and you want it false whenever the session layer lives in FIXT.1.1: the session message types are
  legal on the wire but defined elsewhere, so pruning them here leaves the value list contradicting `fixt-11`.

[`fix-latest`](../fix-packages/fix-latest/pom.xml) is the worked example of both, sanitizing its generated dictionary
with `sanitizeMsgTypeField` off and the session fields named in `keepFields`.

Sanitizing is the second of two cuts, and the smaller one. The first is choosing which messages to generate at all,
which is the message list in [FIX Latest](#fix-latest) above. Cut the message list to what you trade, then sanitize
what that leaves. [Tuning for latency](tuning-for-latency.md#4-ship-a-dictionary-that-holds-only-what-you-use) explains why both matter at runtime.
