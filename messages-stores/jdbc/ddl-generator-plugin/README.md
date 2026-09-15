# Staffix JDBC DDL Generator Plugin

Maven plugin that generates database-specific DDL (Data Definition Language) scripts for FIX message storage using
Hibernate's schema export and Jakarta Persistence API entity models.

## Purpose

This plugin automatically generates SQL schema scripts for the JDBC-based FIX message store. It reads JPA entity
annotations and produces optimized DDL for your target database dialect, eliminating manual schema maintenance across
different database platforms.

## Features

- **Multi-Database Support**: Generate DDL for MySQL, PostgreSQL, Oracle, SQL Server, H2, MariaDB, and more
- **Table Prefix Support**: Add custom prefixes to table names for multi-tenant scenarios
- **Formatted Output**: Human-readable, formatted SQL with optional comments
- **Flexible Configuration**: Control DROP statements, delimiters, and output locations
- **Entity-Driven**: Schema derived from JPA entities ensures consistency with code

## Generated Schema

The plugin generates two tables:

- `fix_messages` - Stores FIX protocol messages with sequence numbers
- `fix_messages_session_state` - Tracks incoming/outgoing sequence numbers per session

## Maven Plugin Setup

### Basic Configuration

Add the plugin to your `pom.xml`:

```xml

<build>
    <plugins>
        <plugin>
            <groupId>org.lolaf.staffix</groupId>
            <artifactId>staffix-messages-store-jdbc-ddl-generator-maven-plugin</artifactId>
            <version>${staffix.version}</version>
            <executions>
                <execution>
                    <id>generate-mysql-ddl</id>
                    <phase>generate-resources</phase>
                    <goals>
                        <goal>generate-ddl</goal>
                    </goals>
                    <configuration>
                        <dialect>org.hibernate.dialect.MySQLDialect</dialect>
                        <outputDirectory>${project.build.directory}/generated-ddl</outputDirectory>
                        <outputFileName>schema-mysql.sql</outputFileName>
                    </configuration>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

### Advanced Configuration

Generate DDL for multiple databases with custom settings:

```xml

<build>
    <plugins>
        <plugin>
            <groupId>org.lolaf.staffix</groupId>
            <artifactId>staffix-messages-store-jdbc-ddl-generator-maven-plugin</artifactId>
            <version>${staffix.version}</version>
            <executions>
                <!-- MySQL -->
                <execution>
                    <id>generate-mysql-ddl</id>
                    <phase>generate-resources</phase>
                    <goals>
                        <goal>generate-ddl</goal>
                    </goals>
                    <configuration>
                        <dialect>org.hibernate.dialect.MySQLDialect</dialect>
                        <outputDirectory>${project.build.directory}/ddl/mysql</outputDirectory>
                        <outputFileName>schema.sql</outputFileName>
                        <formatSql>true</formatSql>
                        <createDropStatements>true</createDropStatements>
                        <delimiter>;</delimiter>
                    </configuration>
                </execution>

                <!-- PostgreSQL -->
                <execution>
                    <id>generate-postgresql-ddl</id>
                    <phase>generate-resources</phase>
                    <goals>
                        <goal>generate-ddl</goal>
                    </goals>
                    <configuration>
                        <dialect>org.hibernate.dialect.PostgreSQLDialect</dialect>
                        <outputDirectory>${project.build.directory}/ddl/postgresql</outputDirectory>
                        <outputFileName>schema.sql</outputFileName>
                        <formatSql>true</formatSql>
                        <createDropStatements>true</createDropStatements>
                    </configuration>
                </execution>

                <!-- Oracle 12c+ -->
                <execution>
                    <id>generate-oracle-ddl</id>
                    <phase>generate-resources</phase>
                    <goals>
                        <goal>generate-ddl</goal>
                    </goals>
                    <configuration>
                        <dialect>org.hibernate.dialect.Oracle12cDialect</dialect>
                        <outputDirectory>${project.build.directory}/ddl/oracle</outputDirectory>
                        <outputFileName>schema.sql</outputFileName>
                        <formatSql>true</formatSql>
                    </configuration>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

### Configuration with Table Prefix

For multi-tenant deployments or namespace isolation:

```xml

<plugin>
    <groupId>org.lolaf.staffix</groupId>
    <artifactId>staffix-messages-store-jdbc-ddl-generator-maven-plugin</artifactId>
    <version>${staffix.version}</version>
    <executions>
        <execution>
            <id>generate-ddl-with-prefix</id>
            <phase>generate-resources</phase>
            <goals>
                <goal>generate-ddl</goal>
            </goals>
            <configuration>
                <dialect>org.hibernate.dialect.MySQLDialect</dialect>
                <outputDirectory>${project.build.directory}/ddl</outputDirectory>
                <outputFileName>schema-prefixed.sql</outputFileName>
                <tablePrefix>myapp</tablePrefix>
                <formatSql>true</formatSql>
                <createDropStatements>false</createDropStatements>
            </configuration>
        </execution>
    </executions>
</plugin>
```

This generates tables named `myapp_fix_messages` and `myapp_fix_messages_session_state`.

## Configuration Parameters

| Parameter              | Type    | Default                                    | Description                           |
|------------------------|---------|--------------------------------------------|---------------------------------------|
| `dialect`              | String  | `org.hibernate.dialect.MySQLDialect`       | Hibernate dialect for target database |
| `outputDirectory`      | File    | `${project.build.directory}/generated-ddl` | Output directory for SQL scripts      |
| `outputFileName`       | String  | `schema.sql`                               | Name of the generated SQL file        |
| `tablePrefix`          | String  | _(empty)_                                  | Optional prefix for table names       |
| `formatSql`            | Boolean | `true`                                     | Format SQL output for readability     |
| `createDropStatements` | Boolean | `true`                                     | Include DROP statements in output     |
| `delimiter`            | String  | `;`                                        | SQL statement delimiter               |

## Supported Database Dialects

| Database   | Dialect Class                                   |
|------------|-------------------------------------------------|
| MySQL      | `org.hibernate.dialect.MySQLDialect`            |
| PostgreSQL | `org.hibernate.dialect.PostgreSQLDialect`       |
| Oracle     | `org.hibernate.dialect.OracleDialect`           |
| SQL Server | `org.hibernate.dialect.SQLServerDialect`        |
| MariaDB    | `org.hibernate.dialect.MariaDBDialect`          |
| H2         | `org.hibernate.dialect.H2Dialect`               |
| SQLite     | `org.hibernate.community.dialect.SQLiteDialect` |

## Usage

### Generate DDL during build

```bash
mvn clean generate-resources
```

The generated SQL files will be in `target/generated-ddl/` (or your configured output directory).

### Generate DDL on demand

```bash
mvn staffix-jdbc-ddl:generate-ddl -Ddialect=org.hibernate.dialect.PostgreSQLDialect
```

### Apply generated schema

```bash
# MySQL
mysql -u username -p database_name < target/ddl/mysql/schema.sql

# PostgreSQL
psql -U username -d database_name -f target/ddl/postgresql/schema.sql

# Oracle
sqlplus username/password@database @target/ddl/oracle/schema.sql
```

## Integration with JDBC Message Store

Use the generated DDL to initialize your database, then configure the JDBC message store:

```java
DataSource dataSource = // ... configure your DataSource with connection pooling
JdbcMessageStoreSettings settings = JdbcMessageStoreSettings.builder()
.dataSource(dataSource)
.tablePrefix("myapp") // Must match DDL generation prefix if provided
.build();

FixMessagesStore messagesStore = settings.instance();
messagesStore.start();
```

## Example Output

Generated MySQL DDL excerpt:

```sql
create table fix_messages
(
    id              bigint       not null auto_increment,
    session_id      varchar(255) not null,
    sequence_number bigint       not null,
    message_data    longblob     not null,
    created_at      timestamp(6) not null,
    primary key (id)
) engine=InnoDB;

create index idx_session_seq on fix_messages (session_id, sequence_number);

alter table fix_messages
    add constraint uk_session_seq unique (session_id, sequence_number);
```
