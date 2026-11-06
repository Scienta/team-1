package no.scienta.cfp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Speaker registration (specs/speakers/spec.md): a speaker reads back as registered, needs a real name and email, has
 * a bio of at most 500 characters, and registers an email once regardless of letter case, also under concurrent
 * registrations. The legacy trailing-slash URLs work, and the old admin search matches names regardless of case and
 * treats its query as text. Runs the real app on a random port with a throwaway database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SpeakersTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.fresh(registry);
    }

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private Api api;

    @BeforeEach
    void api() {
        api = new Api(port);
    }

    @Test
    void aSpeakerReadsBackExactlyAsRegistered() {
        String email = "Ada-" + UUID.randomUUID() + "@Example.com";
        JsonNode created = Api.body(api.post("/speakers",
                "{\"name\":\"Ada Lovelace\",\"email\":\"" + email + "\",\"bio\":\"Wrote the first program.\"}"), 201);
        assertTrue(created.get("id").canConvertToLong(), created.toString());
        assertEquals("Ada Lovelace", created.get("name").asText());
        assertEquals(email, created.get("email").asText());
        assertEquals("Wrote the first program.", created.get("bio").asText());

        JsonNode read = Api.body(api.get("/speakers/" + created.get("id").asLong()), 200);
        assertEquals(created, read);
    }

    @Test
    void theBioIsOptional() {
        JsonNode created = Api.body(api.post("/speakers",
                speaker("No Bio", "nobio-" + UUID.randomUUID() + "@example.com")), 201);
        assertTrue(created.get("bio").isNull(), created.toString());
        assertEquals(created, Api.body(api.get("/speakers/" + created.get("id").asLong()), 200));
    }

    @Test
    void aSpeakerNeedsARealNameAndARealEmail() {
        String email = "x-" + UUID.randomUUID() + "@example.com";
        assertEquals(400, api.post("/speakers", "{}").statusCode());
        assertEquals(400, api.post("/speakers", "{\"email\":\"" + email + "\"}").statusCode());
        assertEquals(400, api.post("/speakers", speaker("   ", email)).statusCode());
        assertEquals(400, api.post("/speakers", "{\"name\":\"Ada\"}").statusCode());
        assertEquals(400, api.post("/speakers", speaker("Ada", "  ")).statusCode());
        assertEquals(400, api.post("/speakers", speaker("Ada", "not-an-email")).statusCode());
    }

    @Test
    void aBioIsAtMost500Characters() {
        String ok = "{\"name\":\"Ada\",\"email\":\"bio-" + UUID.randomUUID() + "@example.com\",\"bio\":\"" + "b".repeat(500) + "\"}";
        String tooLong = "{\"name\":\"Ada\",\"email\":\"bio-" + UUID.randomUUID() + "@example.com\",\"bio\":\"" + "b".repeat(501) + "\"}";
        assertEquals(201, api.post("/speakers", ok).statusCode());
        assertEquals(400, api.post("/speakers", tooLong).statusCode());
    }

    @Test
    void anUnknownSpeakerIsNotFound() {
        assertEquals(404, api.get("/speakers/987654321").statusCode());
    }

    @Test
    void theLegacyTrailingSlashUrlsWork() {
        JsonNode created = Api.body(api.post("/speakers/",
                speaker("Slash", "slash-" + UUID.randomUUID() + "@example.com")), 201);
        long id = created.get("id").asLong();
        assertEquals(created, Api.body(api.get("/speakers/" + id + "/"), 200));
    }

    @Test
    void searchTreatsTheQueryAsTextNotSql() {
        assertEquals(201, api.post("/speakers",
                speaker("Injection Target", "target-" + UUID.randomUUID() + "@example.com")).statusCode());

        JsonNode found = Api.body(api.get("/speakers/search?q=" + encode("' OR '1'='1")), 200);
        assertEquals(0, found.size(), found.toString());
        assertEquals(200, api.get("/speakers/search?q=" + encode("O'Brien")).statusCode());
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    @Test
    void anEmailRegistersOnceRegardlessOfLetterCase() throws Exception {
        String email = "grace-" + UUID.randomUUID() + "@example.com";
        assertEquals(201, register("Grace Hopper", email).statusCode());
        assertEquals(409, register("Grace Hopper", email.toUpperCase()).statusCode());
    }

    @Test
    void concurrentRegistrationsOfOneEmailHaveOneWinner() throws Exception {
        String email = "racer-" + UUID.randomUUID() + "@example.com";
        List<CompletableFuture<HttpResponse<String>>> sent = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String variant = i % 2 == 0 ? email : email.toUpperCase();
            sent.add(http.sendAsync(request("POST", "/speakers", speaker("Racer " + i, variant)),
                    HttpResponse.BodyHandlers.ofString()));
        }
        int created = 0;
        for (CompletableFuture<HttpResponse<String>> f : sent) {
            HttpResponse<String> res = f.join();
            assertTrue(res.statusCode() == 201 || res.statusCode() == 409, res.statusCode() + " " + res.body());
            if (res.statusCode() == 201) {
                created++;
            }
        }
        assertEquals(1, created);
    }

    @Test
    void searchMatchesNamesRegardlessOfCase() throws Exception {
        String name = "Lovelace" + UUID.randomUUID().toString().substring(0, 8);
        assertEquals(201, register("Ada " + name, "ada-" + UUID.randomUUID() + "@example.com").statusCode());

        HttpResponse<String> found = send("GET", "/speakers/search?q=" + name.toLowerCase());
        assertEquals(200, found.statusCode());
        assertTrue(found.body().contains("\"name\":\"Ada " + name + "\""), found.body());
    }

    private HttpResponse<String> register(String name, String email) throws Exception {
        return http.send(request("POST", "/speakers", speaker(name, email)), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> send(String method, String path) throws Exception {
        return http.send(request(method, path, null), HttpResponse.BodyHandlers.ofString());
    }

    private static String speaker(String name, String email) {
        return "{\"name\":\"" + name + "\",\"email\":\"" + email + "\"}";
    }

    private HttpRequest request(String method, String path, String json) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json))
                .build();
    }
}
