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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests that verify DDL generation works correctly across multiple database dialects.
 */
class MultiDialectIntegrationTest {

    @TempDir
    Path tempDir;

    @ParameterizedTest
    @CsvSource({
            "org.hibernate.dialect.MySQLDialect,mysql,varbinary(32768)",
            "org.hibernate.dialect.PostgreSQLDialect,postgresql,bytea",
            "org.hibernate.dialect.H2Dialect,h2,varbinary(32768)",
            "org.hibernate.dialect.MariaDBDialect,mariadb,varbinary(32768)",
            "org.hibernate.community.dialect.SQLiteDialect,sqlite,blob"
    })
    void testDdlGenerationForVariousDialects(String dialect, String name, String binaryType) throws Exception {
        // Given
        File outputDir = tempDir.resolve(name).toFile();
        outputDir.mkdirs();

        GenerateDdlMojo mojo = new GenerateDdlMojo();
        setField(mojo, "dialect", dialect);
        setField(mojo, "outputDirectory", outputDir);
        setField(mojo, "outputFileName", "schema-" + name + ".sql");
        setField(mojo, "formatSql", true);
        setField(mojo, "createDropStatements", true);
        setField(mojo, "delimiter", ";");

        // When
        mojo.execute();

        // Then
        File outputFile = new File(outputDir, "schema-" + name + ".sql");
        assertThat(outputFile).exists();

        String content = Files.readString(outputFile.toPath());
        assertThat(content)
                .as("DDL for %s should contain table definitions", name)
                .isNotEmpty()
                .contains("create table fix_messages")
                .contains("create table fix_messages_session_state")
                .contains("session_id")
                .contains("sequence_number");

        // Verify binary type is appropriate for dialect
        if (!binaryType.isEmpty()) {
            assertThat(content.toLowerCase())
                    .as("DDL for %s should use %s for binary data", name, binaryType)
                    .contains(binaryType.toLowerCase());
        }
    }

    @Test
    void testAllDialectsGenerateSimilarStructure() throws Exception {
        // Given - Generate DDL for multiple dialects
        String[] dialects = {
                "org.hibernate.dialect.MySQLDialect",
                "org.hibernate.dialect.PostgreSQLDialect",
                "org.hibernate.dialect.H2Dialect"
        };

        // When - Generate DDL for each dialect
        for (String dialect : dialects) {
            File outputDir = tempDir.resolve(dialect.substring(dialect.lastIndexOf('.') + 1)).toFile();
            outputDir.mkdirs();

            GenerateDdlMojo mojo = new GenerateDdlMojo();
            setField(mojo, "dialect", dialect);
            setField(mojo, "outputDirectory", outputDir);
            setField(mojo, "outputFileName", "schema.sql");
            setField(mojo, "formatSql", true);
            setField(mojo, "createDropStatements", false);
            setField(mojo, "delimiter", ";");

            mojo.execute();
        }

        // Then - Verify all generated files contain essential structures
        File mysqlFile = tempDir.resolve("MySQLDialect/schema.sql").toFile();
        File postgresFile = tempDir.resolve("PostgreSQLDialect/schema.sql").toFile();
        File h2File = tempDir.resolve("H2Dialect/schema.sql").toFile();

        assertThat(mysqlFile).exists();
        assertThat(postgresFile).exists();
        assertThat(h2File).exists();

        // All should have the same core structure
        String mysqlContent = Files.readString(mysqlFile.toPath());
        String postgresContent = Files.readString(postgresFile.toPath());
        String h2Content = Files.readString(h2File.toPath());

        for (String content : new String[]{mysqlContent, postgresContent, h2Content}) {
            assertThat(content.toLowerCase())
                    .contains("fix_messages")
                    .contains("fix_messages_session_state")
                    .contains("session_id")
                    .contains("sequence_number")
                    .contains("message_data")
                    .contains("incoming_seq_num")
                    .contains("outgoing_seq_num");
        }
    }

    @Test
    void testPrefixWorksSameAcrossDialects() throws Exception {
        // Given
        String tablePrefix = "app";
        System.setProperty("staffix.table.prefix", tablePrefix);

        String[] dialects = {
                "org.hibernate.dialect.MySQLDialect",
                "org.hibernate.dialect.PostgreSQLDialect"
        };

        try {
            // When
            for (String dialect : dialects) {
                File outputDir = tempDir.resolve("prefixed-" + dialect.substring(dialect.lastIndexOf('.') + 1)).toFile();
                outputDir.mkdirs();

                GenerateDdlMojo mojo = new GenerateDdlMojo();
                setField(mojo, "dialect", dialect);
                setField(mojo, "outputDirectory", outputDir);
                setField(mojo, "outputFileName", "schema.sql");
                setField(mojo, "tablePrefix", tablePrefix);
                setField(mojo, "formatSql", true);
                setField(mojo, "createDropStatements", false);
                setField(mojo, "delimiter", ";");

                mojo.execute();

                // Then
                File outputFile = new File(outputDir, "schema.sql");
                assertThat(outputFile).exists();

                String content = Files.readString(outputFile.toPath());
                assertThat(content.toLowerCase())
                        .contains(tablePrefix + "_fix_messages")
                        .contains(tablePrefix + "_fix_messages_session_state");
            }
        } finally {
            System.clearProperty("staffix.table.prefix");
        }
    }

    @Test
    void testSQLServerDialect() throws Exception {
        // Given
        File outputDir = tempDir.resolve("sqlserver").toFile();
        outputDir.mkdirs();

        GenerateDdlMojo mojo = new GenerateDdlMojo();
        setField(mojo, "dialect", "org.hibernate.dialect.SQLServerDialect");
        setField(mojo, "outputDirectory", outputDir);
        setField(mojo, "outputFileName", "schema-sqlserver.sql");
        setField(mojo, "formatSql", true);
        setField(mojo, "createDropStatements", true);
        setField(mojo, "delimiter", ";");

        // When
        mojo.execute();

        // Then
        File outputFile = new File(outputDir, "schema-sqlserver.sql");
        assertThat(outputFile).exists();

        String content = Files.readString(outputFile.toPath());
        assertThat(content)
                .contains("create table fix_messages")
                .contains("create table fix_messages_session_state");

        // SQL Server uses varbinary for binary data
        assertThat(content.toLowerCase())
                .contains("varbinary");
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
