# FIX Sanitizer Maven Plugin

This Maven plugin sanitizes FIX dictionary XML files by removing field definitions that are not referenced in any
message,
component, header, trailer, or group.

This is a typical step to take before using a customized API FIX XML file in a FIX application and with
the [fix-encoders-generator-maven-plugin](../encoders-generator-maven-plugin)
to avoid to generate too many useless fields in the generated API. Those fields are not needed in the application and
will generate additional
and unefficient memory allocation with the custom Staffix data structures designed for ultra fast access

## Usage

Add the plugin to your `pom.xml`:

```xml

<build>
    <plugins>
        <plugin>
            <groupId>org.lolaf.staffix</groupId>
            <artifactId>staffix-fix-dictionary-sanitizer-maven-plugin</artifactId>
            <version>1.0.0-SNAPSHOT</version>
            <executions>
                <execution>
                    <id>sanitize-fix</id>
                    <phase>process-resources</phase>
                    <goals>
                        <goal>sanitize</goal>
                    </goals>
                    <configuration>
                        <inputFile>${project.basedir}/src/main/resources/FIX44.xml</inputFile>
                        <outputFile>${project.build.directory}/sanitized/FIX44-sanitized.xml</outputFile>
                    </configuration>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

Or run it directly from the command line:

```bash
mvn org.lolaf.staffix:staffix-fix-dictionary-sanitizer-maven-plugin:sanitize -DinputFile=src/main/resources/FIX44.xml -DoutputFile=target/FIX44-sanitized.xml
```

## How it Works

The plugin:

1. Parses the input FIX XML file
2. Collects all field names referenced in:
    - `<header>` section
    - `<trailer>` section
    - `<messages>` section (including nested groups)
    - `<components>` section (including nested groups)
3. Removes field definitions from the `<fields>` section that are not referenced
4. Writes the sanitized XML to the output file

## Configuration Parameters

- **inputFile** (required): The input FIX XML file to sanitize
- **outputFile** (required): The output sanitized FIX XML file
