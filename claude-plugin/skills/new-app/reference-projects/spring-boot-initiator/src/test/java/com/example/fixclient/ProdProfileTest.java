package com.example.fixclient;

import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The prod components (file store, async file logger) are only proven by running them, and by what they wrote.
 */
@ActiveProfiles("prod")
class ProdProfileTest extends AbstractFixClientApplicationTest {

    @TempDir
    static Path dataDirectory;

    @DynamicPropertySource
    static void keepTheDataInATemporaryDirectory(DynamicPropertyRegistry registry) {
        registry.add("STAFFIX_DATA_DIR", () -> dataDirectory.toString());
    }

    /**
     * A failing store or logger never stops the session, so only their files prove them.
     */
    @Override
    void thenWhatTheEnvironmentWroteHoldsTheOrder() {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(contentOfFilesUnder(dataDirectory.resolve("store"))).contains("11=order-1");
            assertThat(contentOfFilesUnder(dataDirectory.resolve("log"))).contains("11=order-1");
        });
    }

    private static String contentOfFilesUnder(Path directory) throws IOException {
        StringBuilder content = new StringBuilder();
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path file : (Iterable<Path>) paths.filter(Files::isRegularFile)::iterator) {
                content.append(new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1));
            }
        }
        return content.toString();
    }
}
