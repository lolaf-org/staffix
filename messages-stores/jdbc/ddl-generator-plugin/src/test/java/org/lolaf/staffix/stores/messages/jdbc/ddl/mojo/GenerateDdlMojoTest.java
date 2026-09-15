/*
 * Copyright © 2024-2026 Lolaf.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.lolaf.staffix.stores.messages.jdbc.ddl.mojo;


import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for GenerateDdlMojo.
 * Tests basic DDL generation functionality for various database dialects.
 */
class GenerateDdlMojoTest {

    @TempDir
    Path tempDir;

    @Test
    void testGenerateDdlForMySQL() throws Exception {
        // Given
        File outputDir = tempDir.resolve("mysql").toFile();
        outputDir.mkdirs();

        GenerateDdlMojo mojo = new GenerateDdlMojo();
        setField(mojo, "dialect", "org.hibernate.dialect.MySQLDialect");
        setField(mojo, "outputDirectory", outputDir);
        setField(mojo, "outputFileName", "schema-mysql.sql");
        setField(mojo, "formatSql", true);
        setField(mojo, "createDropStatements", true);
        setField(mojo, "delimiter", ";");

        // When
        mojo.execute();

        // Then
        File outputFile = new File(outputDir, "schema-mysql.sql");
        assertThat(outputFile).exists();

        String content = Files.readString(outputFile.toPath());
        assertThat(content)
                .isNotEmpty()
                .contains("create table fix_messages")
                .contains("create table fix_messages_session_state")
                .contains("session_id")
                .contains("sequence_number")
                .contains("message_data")
                .contains("incoming_seq_num")
                .contains("outgoing_seq_num");
    }

    @Test
    void testGenerateDdlForPostgreSQL() throws Exception {
        // Given
        File outputDir = tempDir.resolve("postgresql").toFile();
        outputDir.mkdirs();

        GenerateDdlMojo mojo = new GenerateDdlMojo();
        setField(mojo, "dialect", "org.hibernate.dialect.PostgreSQLDialect");
        setField(mojo, "outputDirectory", outputDir);
        setField(mojo, "outputFileName", "schema-postgres.sql");
        setField(mojo, "formatSql", true);
        setField(mojo, "createDropStatements", true);
        setField(mojo, "delimiter", ";");

        // When
        mojo.execute();

        // Then
        File outputFile = new File(outputDir, "schema-postgres.sql");
        assertThat(outputFile).exists();

        String content = Files.readString(outputFile.toPath());

        assertThat(content)
                .isNotEmpty()
                .contains("create table fix_messages")
                .contains("create table fix_messages_session_state")
                .contains("bytea"); // PostgreSQL binary type
    }

    @Test
    void testGenerateDdlForOracle() throws Exception {
        // Given
        File outputDir = tempDir.resolve("oracle").toFile();
        outputDir.mkdirs();

        GenerateDdlMojo mojo = new GenerateDdlMojo();
        setField(mojo, "dialect", "org.hibernate.dialect.OracleDialect");
        setField(mojo, "outputDirectory", outputDir);
        setField(mojo, "outputFileName", "schema-oracle.sql");
        setField(mojo, "formatSql", true);
        setField(mojo, "createDropStatements", true);
        setField(mojo, "delimiter", ";");

        // When
        mojo.execute();

        // Then
        File outputFile = new File(outputDir, "schema-oracle.sql");
        assertThat(outputFile).exists();

        String content = Files.readString(outputFile.toPath());
        assertThat(content)
                .isNotEmpty()
                .contains("create table fix_messages")
                .contains("create table fix_messages_session_state")
                .contains("message_data blob");
    }

    @Test
    void testGenerateDdlWithTablePrefix() throws Exception {
        // Given
        File outputDir = tempDir.resolve("prefixed").toFile();
        outputDir.mkdirs();

        String tablePrefix = "test";
        System.setProperty("staffix.table.prefix", tablePrefix);

        GenerateDdlMojo mojo = new GenerateDdlMojo();
        setField(mojo, "dialect", "org.hibernate.dialect.MySQLDialect");
        setField(mojo, "outputDirectory", outputDir);
        setField(mojo, "outputFileName", "schema-prefixed.sql");
        setField(mojo, "tablePrefix", tablePrefix);
        setField(mojo, "formatSql", true);
        setField(mojo, "createDropStatements", false);
        setField(mojo, "delimiter", ";");

        // When
        mojo.execute();

        // Then
        File outputFile = new File(outputDir, "schema-prefixed.sql");
        assertThat(outputFile).exists();

        String content = Files.readString(outputFile.toPath());
        assertThat(content)
                .isNotEmpty()
                .contains("create table " + tablePrefix + "_fix_messages")
                .contains("create table " + tablePrefix + "_fix_messages_session_state");

        // Cleanup
        System.clearProperty("staffix.table.prefix");
    }

    @Test
    void testGenerateDdlWithoutDropStatements() throws Exception {
        // Given
        File outputDir = tempDir.resolve("no-drop").toFile();
        outputDir.mkdirs();

        GenerateDdlMojo mojo = new GenerateDdlMojo();
        setField(mojo, "dialect", "org.hibernate.dialect.MySQLDialect");
        setField(mojo, "outputDirectory", outputDir);
        setField(mojo, "outputFileName", "schema-no-drop.sql");
        setField(mojo, "formatSql", true);
        setField(mojo, "createDropStatements", false);
        setField(mojo, "delimiter", ";");

        // When
        mojo.execute();

        // Then
        File outputFile = new File(outputDir, "schema-no-drop.sql");
        assertThat(outputFile).exists();

        String content = Files.readString(outputFile.toPath());
        assertThat(content)
                .isNotEmpty()
                .contains("create table")
                .doesNotContainIgnoringCase("drop table");
    }

    @Test
    void testGenerateDdlForH2Database() throws Exception {
        // Given
        File outputDir = tempDir.resolve("h2").toFile();
        outputDir.mkdirs();

        GenerateDdlMojo mojo = new GenerateDdlMojo();
        setField(mojo, "dialect", "org.hibernate.dialect.H2Dialect");
        setField(mojo, "outputDirectory", outputDir);
        setField(mojo, "outputFileName", "schema-h2.sql");
        setField(mojo, "formatSql", true);
        setField(mojo, "createDropStatements", true);
        setField(mojo, "delimiter", ";");

        // When
        mojo.execute();

        // Then
        File outputFile = new File(outputDir, "schema-h2.sql");
        assertThat(outputFile).exists();

        String content = Files.readString(outputFile.toPath());
        assertThat(content)
                .isNotEmpty()
                .contains("create table fix_messages")
                .contains("create table fix_messages_session_state");
    }

    @Test
    void testOutputDirectoryCreatedIfNotExists() throws Exception {
        // Given
        File outputDir = tempDir.resolve("auto-created/nested/dir").toFile();
        assertThat(outputDir).doesNotExist();

        GenerateDdlMojo mojo = new GenerateDdlMojo();
        setField(mojo, "dialect", "org.hibernate.dialect.MySQLDialect");
        setField(mojo, "outputDirectory", outputDir);
        setField(mojo, "outputFileName", "schema.sql");
        setField(mojo, "formatSql", true);
        setField(mojo, "createDropStatements", true);
        setField(mojo, "delimiter", ";");

        // When
        mojo.execute();

        // Then
        assertThat(outputDir).exists();
        assertThat(new File(outputDir, "schema.sql")).exists();
    }

    @Test
    void testGeneratedDdlContainsIndexes() throws Exception {
        // Given
        File outputDir = tempDir.resolve("indexes").toFile();
        outputDir.mkdirs();

        GenerateDdlMojo mojo = new GenerateDdlMojo();
        setField(mojo, "dialect", "org.hibernate.dialect.MySQLDialect");
        setField(mojo, "outputDirectory", outputDir);
        setField(mojo, "outputFileName", "schema-indexes.sql");
        setField(mojo, "formatSql", true);
        setField(mojo, "createDropStatements", false);
        setField(mojo, "delimiter", ";");

        // When
        mojo.execute();

        // Then
        File outputFile = new File(outputDir, "schema-indexes.sql");
        assertThat(outputFile).exists();

        String content = Files.readString(outputFile.toPath());
        assertThat(content)
                .contains("idx_session_seq") // Composite index on fix_messages
                .contains("idx_session");     // Index on fix_messages_session_state
    }

    /**
     * Helper method to set private fields on the mojo using reflection.
     */
    private void setField(Object target, String fieldName, Object value) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
