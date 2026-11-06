package no.scienta.cfp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiTest {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        POSTGRES.start();
        TestDatabase.pointAt(registry, POSTGRES, POSTGRES.getDatabaseName());
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void openapiYamlIsServed() throws Exception {
        HttpResponse<String> res = get("/openapi.yaml");
        assertEquals(200, res.statusCode());
        assertTrue(res.body().contains("openapi:"));
        assertTrue(res.body().contains("CFP API"));
    }

    @Test
    void swaggerUiIsServed() throws Exception {
        HttpResponse<String> res = get("/swagger-ui.html");
        // springdoc redirects to /swagger-ui/index.html
        assertTrue(res.statusCode() == 200 || res.statusCode() == 302,
                "expected 200 or redirect, got " + res.statusCode());
        if (res.statusCode() == 302) {
            HttpResponse<String> index = get("/swagger-ui/index.html");
            assertEquals(200, index.statusCode());
            assertTrue(index.body().toLowerCase().contains("swagger"));
        } else {
            assertTrue(res.body().toLowerCase().contains("swagger"));
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
