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

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.boot.spi.MetadataImplementor;
import org.hibernate.cfg.JdbcSettings;
import org.hibernate.service.ServiceRegistry;
import org.hibernate.tool.hbm2ddl.SchemaExport;
import org.hibernate.tool.schema.TargetType;
import org.lolaf.staffix.stores.messages.jdbc.ddl.entity.FixMessage;
import org.lolaf.staffix.stores.messages.jdbc.ddl.entity.FixMessageSessionState;

import java.io.File;
import java.nio.file.Files;
import java.util.EnumSet;

/**
 * Maven Mojo to generate DDL scripts for FIX message storage schema.
 * Uses Hibernate's schema export capabilities to generate database-specific DDL.
 */
@Mojo(name = "generate-ddl", defaultPhase = LifecyclePhase.GENERATE_RESOURCES)
public class GenerateDdlMojo extends AbstractMojo {

    /**
     * Target database dialect. Supported values:
     * - MySQLDialect (MySQL)
     * - PostgreSQLDialect (PostgreSQL 10+)
     * - OracleDialect (Oracle 12c+)
     * - SQLServerDialect (SQL Server 2012+)
     * - H2Dialect (H2 Database)
     * - MariaDBDialect (MariaDB)
     */
    @Parameter(property = "dialect")
    private String dialect;

    /**
     * Output directory for generated DDL scripts.
     */
    @Parameter(property = "outputDirectory", defaultValue = "${project.build.directory}/generated-ddl")
    private File outputDirectory;

    /**
     * Output file name for the DDL script.
     */
    @Parameter(property = "outputFileName", defaultValue = "schema.sql")
    private String outputFileName;

    /**
     * Table name prefix (optional).
     * If specified, tables will be named {prefix}_fix_messages and {prefix}_fix_messages_session_state.
     */
    @Parameter(property = "tablePrefix")
    private String tablePrefix;

    /**
     * Whether to format the output SQL.
     */
    @Parameter(property = "formatSql", defaultValue = "true")
    private boolean formatSql;

    /**
     * Whether to include DROP statements in the output.
     */
    @Parameter(property = "createDropStatements", defaultValue = "true")
    private boolean createDropStatements;

    /**
     * Delimiter to use between SQL statements.
     */
    @Parameter(property = "delimiter", defaultValue = ";")
    private String delimiter;

    @Override
    public void execute() throws MojoExecutionException {
        try {
            getLog().info("Generating DDL for dialect: " + dialect);
            getLog().info("Output directory: " + outputDirectory.getAbsolutePath());
            getLog().info("Output file: " + outputFileName);

            if (tablePrefix != null && !tablePrefix.isEmpty()) {
                getLog().info("Using table prefix: " + tablePrefix);
            }

            // Ensure output directory exists
            if (!outputDirectory.exists() && !outputDirectory.mkdirs()) {
                throw new MojoExecutionException("Failed to create output directory: " + outputDirectory);
            }

            // Configure Hibernate
            StandardServiceRegistryBuilder registryBuilder = new StandardServiceRegistryBuilder();
            registryBuilder.applySetting("hibernate.dialect", dialect);
            registryBuilder.applySetting("hibernate.format_sql", String.valueOf(formatSql));
            registryBuilder.applySetting("hibernate.use_sql_comments", "true");
            registryBuilder.applySetting(JdbcSettings.ALLOW_METADATA_ON_BOOT, "false");

            if (tablePrefix != null && !tablePrefix.isEmpty()) {
                registryBuilder.applySetting("hibernate.physical_naming_strategy",
                        PrefixedPhysicalNamingStrategy.class.getName());
                registryBuilder.applySetting("staffix.table.prefix", tablePrefix);
            }

            ServiceRegistry serviceRegistry = registryBuilder.build();

            // Add annotated classes
            MetadataSources metadataSources = new MetadataSources(serviceRegistry);
            metadataSources.addAnnotatedClass(FixMessage.class);
            metadataSources.addAnnotatedClass(FixMessageSessionState.class);

            MetadataImplementor metadata = (MetadataImplementor) metadataSources.buildMetadata();

            // delete
            File targetFile = new File(outputDirectory, outputFileName);
            if (!Files.deleteIfExists(targetFile.toPath())) {
                getLog().info("Unable to delete target file " + targetFile);
            }

            // Configure schema export
            SchemaExport schemaExport = new SchemaExport();
            schemaExport.setDelimiter(delimiter);
            schemaExport.setOutputFile(targetFile.getAbsolutePath());
            schemaExport.setFormat(formatSql);

            // Generate DDL
            EnumSet<TargetType> targetTypes = EnumSet.of(TargetType.SCRIPT);
            SchemaExport.Action action = createDropStatements
                    ? SchemaExport.Action.BOTH
                    : SchemaExport.Action.CREATE;

            schemaExport.execute(targetTypes, action, metadata);

            if (schemaExport.getExceptions().isEmpty()) {
                getLog().info("DDL generation completed successfully");
                getLog().info("Generated file: " + new File(outputDirectory, outputFileName).getAbsolutePath());
            } else {
                getLog().error("DDL generation completed with errors:");
                for (Object e : schemaExport.getExceptions()) {
                    Exception ex = (Exception) e;
                    getLog().error("Failed to generate schema", ex);
                }
                throw new MojoExecutionException("DDL generation failed with errors");
            }

        } catch (Exception e) {
            throw new MojoExecutionException("Failed to generate DDL", e);
        }
    }
}
