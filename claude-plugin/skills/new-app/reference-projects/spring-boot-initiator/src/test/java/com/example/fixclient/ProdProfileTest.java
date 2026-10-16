package com.example.fixclient;

import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;

/**
 * The prod components (file store, async file logger) are only proven by running them.
 */
@ActiveProfiles("prod")
class ProdProfileTest extends AbstractFixClientApplicationTest {

    @TempDir
    static Path dataDirectory;

    @DynamicPropertySource
    static void keepTheDataInATemporaryDirectory(DynamicPropertyRegistry registry) {
        registry.add("STAFFIX_DATA_DIR", () -> dataDirectory.toString());
    }
}
