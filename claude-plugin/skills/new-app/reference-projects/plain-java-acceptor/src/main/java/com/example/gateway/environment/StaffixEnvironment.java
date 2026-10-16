package com.example.gateway.environment;

import org.lolaf.staffix.api.FixEngineBuilder;

import java.nio.file.Path;

/**
 * The engine components that differ between environments: message store, message logger, settings store and, in
 * production, monitoring and alerting. Selected by the {@code STAFFIX_ENV} environment variable, {@code dev} when unset;
 * prod keeps its files under {@code STAFFIX_DATA_DIR}.
 */
public interface StaffixEnvironment {

    void addComponents(FixEngineBuilder.FixEngineBuilderBuilder engine);

    static StaffixEnvironment fromEnvironmentVariable() {
        String name = System.getenv().getOrDefault("STAFFIX_ENV", "dev");
        switch (name) {
            case "dev":
                return new DevEnvironment();
            case "prod":
                return new ProdEnvironment(Path.of(requiredEnvironmentVariable("STAFFIX_DATA_DIR")));
            default:
                throw new IllegalArgumentException("Unknown STAFFIX_ENV " + name + ", expected dev or prod");
        }
    }

    private static String requiredEnvironmentVariable(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set in the prod environment");
        }
        return value;
    }
}
