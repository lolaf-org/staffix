package com.example.gateway.environment;

import org.lolaf.staffix.api.FixEngineBuilder;

/**
 * The engine components that differ between environments: message store, message logger, settings store and, in
 * production, monitoring and alerting. Selected by the {@code STAFFIX_ENV} environment variable, {@code dev} when unset.
 */
public interface StaffixEnvironment {

    void addComponents(FixEngineBuilder.FixEngineBuilderBuilder engine);

    static StaffixEnvironment fromEnvironmentVariable() {
        String name = System.getenv().getOrDefault("STAFFIX_ENV", "dev");
        switch (name) {
            case "dev":
                return new DevEnvironment();
            case "prod":
                return new ProdEnvironment();
            default:
                throw new IllegalArgumentException("Unknown STAFFIX_ENV " + name + ", expected dev or prod");
        }
    }
}
