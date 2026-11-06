package no.scienta.cfp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A small HTTP client for the tests that run the real app on a random port: sends JSON, reads JSON, and creates
 * speakers and talks to test against.
 */
final class Api {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newHttpClient();
    private final int port;

    Api(int port) {
        this.port = port;
    }

    HttpResponse<String> get(String path) {
        return send("GET", path, null);
    }

    HttpResponse<String> post(String path, String json) {
        return send("POST", path, json);
    }

    HttpResponse<String> put(String path, String json) {
        return send("PUT", path, json);
    }

    HttpResponse<String> send(String method, String path, String json) {
        try {
            return http.send(request(method, path, json), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    CompletableFuture<HttpResponse<String>> sendAsync(String method, String path, String json) {
        return http.sendAsync(request(method, path, json), HttpResponse.BodyHandlers.ofString());
    }

    /** The body of a response that must have the given status. */
    static JsonNode body(HttpResponse<String> res, int expectedStatus) {
        assertEquals(expectedStatus, res.statusCode(), res.body());
        return json(res.body());
    }

    static JsonNode json(String body) {
        return JSON.readTree(body);
    }

    /** Registers a speaker with a unique email and returns its id. */
    long newSpeaker() {
        String email = "speaker-" + UUID.randomUUID() + "@example.com";
        return body(post("/speakers", "{\"name\":\"Grace Hopper\",\"email\":\"" + email + "\"}"), 201)
                .get("id").asLong();
    }

    /** Submits a talk and returns it. */
    JsonNode newTalk(long speakerId, String format) {
        return body(post("/talks", "{\"title\":\"A talk\",\"format\":\"" + format
                + "\",\"level\":\"BEGINNER\",\"speakerId\":" + speakerId + "}"), 201);
    }

    /** Rates a talk and returns the rating. */
    JsonNode rate(long talkId, long reviewerId, int score) {
        return body(put("/talks/" + talkId + "/ratings/" + reviewerId, "{\"score\":" + score + "}"), 200);
    }

    private HttpRequest request(String method, String path, String json) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json))
                .build();
    }
}
