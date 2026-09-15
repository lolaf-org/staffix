# FIX Encoders Generator Maven Plugin

A Maven plugin that parses FIX XML dictionary files and generates type-safe Java encoders, message types, field
definitions,
and registries compatible with the Staffix API.

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

Add the plugin to your `pom.xml`:

```xml

<build>
    <plugins>
        <plugin>
            <groupId>org.lolaf.staffix</groupId>
            <artifactId>fix-encoders-generator-maven-plugin</artifactId>
            <version>1.0.0-SNAPSHOT</version>
            <configuration>
                <dictionaryFile>${project.basedir}/src/main/resources/FIX44.xml</dictionaryFile>
                <sourcesOutputDirectory>${project.build.directory}/generated-sources/</sourcesOutputDirectory>
                <packageName>org.lolaf.staffix.fix44</packageName>
                <dictionaryId>default</dictionaryId>
            </configuration>
            <executions>
                <execution>
                    <phase>generate-sources</phase>
                    <goals>
                        <goal>code-generator</goal>
                    </goals>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

### Configuration Parameters

| Parameter                      | Required | Default                              | Description                                                                           |
|--------------------------------|----------|--------------------------------------|---------------------------------------------------------------------------------------|
| `dictionaryFile`               | Yes      | -                                    | Path to the FIX XML dictionary file                                                   |
| `sourcesOutputDirectory`       | Yes      | -                                    | Directory where Java source files will be generated                                   |
| `resourcesOutputDirectory`     | No       | `${project.build.directory}/classes` | Directory where resource files (field validation info) will be generated              |
| `packageName`                  | Yes      | -                                    | Base package name for generated classes                                               |
| `dictionaryId`                 | Yes      | -                                    | Identifier for this dictionary (e.g., "default", "custom")                            |
| `addFIXEngineAndAppInfoFields` | No       | `true`                               | Whether to add FIX engine and application info fields (1600-1605) into generated code |

### Example Configuration for FIX 4.4

```xml

<configuration>
    <dictionaryFile>${project.basedir}/src/main/resources/FIX44.xml</dictionaryFile>
    <sourcesOutputDirectory>${project.build.directory}/generated-sources/</sourcesOutputDirectory>
    <packageName>org.lolaf.staffix.fix44</packageName>
    <dictionaryId>default</dictionaryId>
    <addFIXEngineAndAppInfoFields>true</addFIXEngineAndAppInfoFields>
</configuration>
```

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
// Generated encoder provides fluent API
NewOrderSingleEncoder encoder = new NewOrderSingleEncoder();
encoder.

setClOrdID("ORDER123")
       .

setSymbol("AAPL")
       .

setSide(Side.BUY)
       .

setOrderQty(100)
       .

setOrdType(OrdType.LIMIT)
       .

setPrice(150.50);
```

## Running the Plugin

The plugin runs automatically during the Maven build lifecycle:

```bash
mvn clean compile
```

Or run it directly:

```bash
mvn fix-encoders-generator:code-generator
```

## Notes

- Admin messages (e.g., Logon, Heartbeat) do not have encoders generated - they use standard implementations
- Repeating groups are fully supported with nested field generation
- Components are expanded inline into messages and groups
- Have a look at the [staffix-fix-dictionary-sanitizer-maven-plugin](../fix-encoders-generator-maven-plugin) if you work
  with
  FIX
  dictionaries tailored for your
  own FIX API and want to have a clean dictionary file for the generator input and minimize generation of useless code.

## Support for FIXT

The plugin supports both regular FIX versions and FIXT (FIX Transport) protocol:

- Set `type="FIXT"` in the XML root element for FIXT dictionaries
- Different registries are generated for FIXT vs regular FIX
