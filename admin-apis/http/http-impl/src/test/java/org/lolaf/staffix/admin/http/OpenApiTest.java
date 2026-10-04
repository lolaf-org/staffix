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
package org.lolaf.staffix.admin.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.admin.AdminApi;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpenApiTest {

    private static final Set<String> HTTP_METHODS = Set.of("get", "put", "post", "delete", "patch", "head", "options");

    private static JsonNode openApi() throws Exception {
        try (InputStream in = HttpAdminServer.class.getResourceAsStream("openapi.yaml")) {
            return new ObjectMapper(new YAMLFactory()).readTree(in);
        }
    }

    @Test
    void theDocumentDescribesEveryRouteAndNoOther() throws Exception {
        List<String> documented = new ArrayList<>();
        for (Iterator<Map.Entry<String, JsonNode>> paths = openApi().get("paths").fields(); paths.hasNext(); ) {
            Map.Entry<String, JsonNode> path = paths.next();
            path.getValue().fieldNames().forEachRemaining(method -> {
                if (HTTP_METHODS.contains(method)) {
                    documented.add(method.toUpperCase() + " " + path.getKey());
                }
            });
        }

        assertThat(documented).containsExactlyInAnyOrderElementsOf(
                new AdminApiHandler(mock(AdminApi.class), "alpha-token").routeTemplates());
    }

    @Test
    void everyReferenceResolves() throws Exception {
        JsonNode openApi = openApi();
        List<String> references = new ArrayList<>();
        collectReferences(openApi, references);

        assertThat(references).isNotEmpty().allSatisfy(reference ->
                assertThat(openApi.at(reference.substring(1)).isMissingNode()).as(reference).isFalse());
    }

    private static void collectReferences(JsonNode node, List<String> references) {
        if (node.has("$ref")) {
            references.add(node.get("$ref").asText());
        }
        node.forEach(child -> collectReferences(child, references));
    }

    @Test
    void theDocumentIsServedWithoutAToken() throws Exception {
        HttpAdminApi exporter = new HttpAdminApi(HttpAdminApiSettings.builder()
                .bindAddress("127.0.0.1").port(0).apiToken("alpha-token").build());
        AdminApi adminApi = mock(AdminApi.class);
        when(adminApi.getInstanceId()).thenReturn("alpha-engine");
        exporter.export(adminApi);
        try {
            HttpResponse<byte[]> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + exporter.getAddress().getPort() + "/openapi.yaml")).build(),
                    HttpResponse.BodyHandlers.ofByteArray());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Type")).hasValue("application/yaml");
            try (InputStream in = HttpAdminServer.class.getResourceAsStream("openapi.yaml")) {
                assertThat(response.body()).isEqualTo(in.readAllBytes());
            }
        } finally {
            exporter.shutdown(Deadline.of(Duration.ofSeconds(1)));
        }
    }
}
