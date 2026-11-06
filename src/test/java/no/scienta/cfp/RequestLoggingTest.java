package no.scienta.cfp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Every request is logged on one line with method, path, status and both bodies, error responses get a second line
 * with the error body, and the response still reaches the client unchanged.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class RequestLoggingTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.fresh(registry);
    }

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void aRequestIsLoggedWithBothBodies(CapturedOutput output) throws Exception {
        String email = "log-" + UUID.randomUUID() + "@example.com";
        String json = "{\"name\":\"Ada\",\"email\":\"" + email + "\"}";
        HttpResponse<String> res = send("POST", "/speakers?via=test", json);

        assertEquals(201, res.statusCode(), res.body());
        assertTrue(res.body().contains(email), "the client still gets the body: " + res.body());
        String line = lineContaining(output, "POST /speakers?via=test -> 201");
        assertTrue(line.contains("req=" + json), line);
        assertTrue(line.contains("res={\"id\":"), line);
    }

    @Test
    void anErrorIsLoggedWithTheErrorBody(CapturedOutput output) throws Exception {
        HttpResponse<String> res = send("GET", "/talks/987654321", null);

        assertEquals(404, res.statusCode());
        assertTrue(res.body().contains("talk not found"), "the client still gets the error: " + res.body());
        lineContaining(output, "GET /talks/987654321 -> 404");
        String error = lineContaining(output, "GET /talks/987654321 -> 404 error");
        assertTrue(error.contains("talk not found"), error);
    }

    @Test
    void healthyHealthProbesAreNotLogged(CapturedOutput output) throws Exception {
        assertEquals(200, send("GET", "/health", null).statusCode());
        assertFalse(output.getOut().contains("GET /health -> 200"), "health probe was logged");
    }

    private static String lineContaining(CapturedOutput output, String text) {
        return output.getOut().lines()
                .filter(l -> l.contains(text))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no log line with '" + text + "'"));
    }

    private HttpResponse<String> send(String method, String path, String json) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (json == null) {
            req.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            req.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json));
        }
        return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }
}
