# FIX Encoders Generator Maven Plugin

A Maven plugin that parses FIX XML dictionary files and generates type-safe Java encoders, message types, field
definitions and registries compatible with the Staffix API.

## Overview

The FIX (Financial Information eXchange) protocol uses XML dictionaries to define message structures, fields, and data
types. This plugin automates the generation of Staffix encoder API code from these dictionaries, providing:

- **Type-safe field definitions** for all FIX fields
- **Message encoders** for creating and encoding FIX messages
- **Message type registries** for message type lookups
- **Field registries** for field metadata and validation
- **Message field order registries** for proper field sequencing

## What Does This Plugin Do?

The plugin processes a FIX XML dictionary file (e.g., `FIX44.xml`, `FIX50.xml`) and generates:

1. **Field Classes** (`packageName.fields.*`)
    - One Java class per FIX field (e.g., `BeginString`, `MsgType`, `ClOrdID`)
    - Includes field number, name, type, and validation information
    - Supports enumerated values where applicable

2. **Message Type Enums** (`packageName.msg.MessageTypes`)
    - Enum of all message types in the FIX version
    - Maps message names to their type codes (e.g., `Logon("A")`, `NewOrderSingle("D")`)

3. **Message Encoders** (`packageName.encoders.*`)
    - Type-safe builder-style encoders for each message type
    - Fluent API for setting fields and groups
    - Handles repeating groups and components
    - Only generated for application messages (not admin messages)

4. **Registries**
    - `FieldsRegistryImpl`: Registry of all field metadata
    - `MessageTypeRegistryImpl`: Registry of message types
    - `MessageFieldsRegistryImpl`: Registry of field order per message type
    - All registered via Java SPI (Service Provider Interface)

5. **Field Validation Metadata**
    - Per-message field validation info files
    - Contains field codes, parent field codes (for groups), and required flags
    - Used for runtime validation

## Usage

### Basic Configuration

Staffix ships the standard dictionaries as artifacts (`staffix-fix-44` carries `FIX44.xml`, and so on). List the one
you need among the plugin's dependencies and read it from the classpath:

```xml
<build>
    <plugins>
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
                    <goals>
                        <goal>code-generator</goal>
                    </goals>
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
    </plugins>
</build>
```

A dictionary of your own is a file path instead, relative to the project:
`<dictionaryFile>src/main/dictionaries/MYFIX44.xml</dictionaryFile>`.

Several dictionaries can be generated into one module, one execution each with its own `packageName`. From FIX 5.0
the session layer is FIXT.1.1, which is not generated: add `staffix-fixt-11`, which ships its encoders, as a
dependency of your project.

### Configuration Parameters

| Parameter                      | Required | Default                              | Description                                                                                  |
|--------------------------------|----------|--------------------------------------|----------------------------------------------------------------------------------------------|
| `dictionaryFile`               | Yes      | -                                    | The dictionary: `classpath:<resource>` from the plugin's dependencies, or a file path        |
| `sourcesOutputDirectory`       | Yes      | -                                    | Directory where Java source files will be generated                                          |
| `resourcesOutputDirectory`     | No       | `${project.build.directory}/classes` | Directory for the field validation info and the SPI registrations                            |
| `packageName`                  | Yes      | -                                    | Base package name for generated classes                                                      |
| `dictionaryId`                 | No       | `default`                            | The id a session names to use this dictionary; `default` is the one sessions use unless set |
| `addFIXEngineAndAppInfoFields` | No       | `true`                               | Whether to add FIX engine and application info fields (1600-1605) into generated code        |
| `testSources`                  | No       | `false`                              | Adds the sources to the test compilation; set `resourcesOutputDirectory` to test output too |

## Generated Code Structure

For a dictionary with `packageName=org.lolaf.staffix.fix44`, the plugin generates:

```
org.lolaf.staffix.fix44/
├── fields/
│   ├── BeginString.java
│   ├── MsgType.java
│   ├── ClOrdID.java
│   ├── Symbol.java
│   └── ... (one class per field)
│   └── FieldsRegistryImpl.java
├── msg/
│   ├── MessageTypes.java
│   ├── MessageTypeRegistryImpl.java
│   ├── MessageFieldsRegistryImpl.java
│   └── AbstractMessageFieldsRegistry.java
└── encoders/
    ├── NewOrderSingleEncoder.java
    ├── ExecutionReportEncoder.java
    └── ... (one encoder per app message)
```

## How It Works

1. **XML Parsing**: Reads the FIX dictionary XML file and parses:
    - `<header>`: Header fields
    - `<trailer>`: Trailer fields
    - `<messages>`: All message definitions
    - `<components>`: Reusable components
    - `<fields>`: Field definitions with types and enums

2. **Version Detection**: Determines FIX version from XML attributes:
    - Supports standard FIX versions (4.0 through 5.0 SP2)
    - Supports FIXT (FIX Transport) protocol

3. **Code Generation**: Uses Apache Velocity templates to generate:
    - Field classes with proper types and validation
    - Message encoders with fluent builder APIs
    - Registries for runtime lookups

4. **Field Tracking**: Only generates fields that are actually used in messages to minimize code size

5. **SPI Registration**: Automatically registers generated registries via Java Service Provider Interface

## Example Generated Encoder Usage

```java
NewOrderSingleEncoder encoder = session.newEncoder(NewOrderSingleEncoder.class).begin()
        .setSymbol("AAPL")
        .setSide(Side.SideValues.BUY)
        .setOrdType(OrdType.OrdTypeValues.LIMIT)
        .setPrice(150.50);
session.send(encoder, null);
```

## Running the Plugin

The goal binds to `generate-sources` by default, so it runs with the build:

```bash
mvn clean compile
```

## Notes

- Admin messages (e.g., Logon, Heartbeat) do not have encoders generated - they use standard implementations
- Repeating groups are fully supported with nested field generation
- Components are expanded inline into messages and groups
- Have a look at the [staffix-fix-dictionary-sanitizer-maven-plugin](../fix-dictionary-sanitizer-maven-plugin) if you
  work with FIX dictionaries tailored for your own FIX API and want a clean dictionary file for the generator input,
  minimizing the generation of useless code.

## Support for FIXT

The plugin supports both regular FIX versions and FIXT (FIX Transport) protocol:

- Set `type="FIXT"` in the XML root element for FIXT dictionaries
- Different registries are generated for FIXT vs regular FIX
