package no.scienta.cfp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Talk submissions (specs/talks/spec.md): a talk reads back as submitted, is SUBMITTED, has the legacy timestamp
 * formats, gets its duration from the format, accepts only exact formats and levels, and a speaker submits at most 3
 * talks, also when the submissions arrive at the same time. Runs the real app on a random port with a throwaway
 * database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TalksTest {

    private static final Pattern ID = Pattern.compile("\"id\":(\\d+)");

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
    void aTalkReadsBackAsSubmittedWithItsStatusAndTimestamps() {
        long speaker = api.newSpeaker();
        JsonNode talk = Api.body(api.post("/talks", talk("Legacy is a feeling", "PRESENTATION", "ADVANCED", speaker)), 201);

        assertEquals("Legacy is a feeling", talk.get("title").asString());
        assertEquals("PRESENTATION", talk.get("format").asString());
        assertEquals("ADVANCED", talk.get("level").asString());
        assertEquals(speaker, talk.get("speakerId").asLong());
        assertEquals("SUBMITTED", talk.get("status").asString());
        String submittedAt = talk.get("submittedAt").asString();
        assertTrue(SUBMITTED_AT.matcher(submittedAt).matches(), submittedAt);
        assertEquals(submittedAt.substring(0, 10), talk.get("submittedOn").asString());
        long age = Duration.between(OffsetDateTime.parse(submittedAt), OffsetDateTime.now(ZoneOffset.UTC)).abs().toSeconds();
        assertTrue(age < 60, "submittedAt is " + age + " s off the clock");

        assertEquals(talk, Api.body(api.get("/talks/" + talk.get("id").asLong()), 200));
    }

    @Test
    void theFormatDecidesTheDurationNotTheClient() {
        Map<String, Integer> minutes = Map.of("LIGHTNING_10", 10, "LIGHTNING_20", 20, "PRESENTATION", 45, "WORKSHOP", 120);
        for (Map.Entry<String, Integer> e : minutes.entrySet()) {
            long speaker = api.newSpeaker();
            String json = "{\"title\":\"T\",\"format\":\"" + e.getKey()
                    + "\",\"level\":\"BEGINNER\",\"speakerId\":" + speaker + ",\"durationMinutes\":999}";
            JsonNode talk = Api.body(api.post("/talks", json), 201);
            assertEquals(e.getValue().intValue(), talk.get("durationMinutes").asInt(), e.getKey());
        }
    }

    @Test
    void formatsAndLevelsAreExactCapitalsAndTitleAndSpeakerAreRequired() {
        long speaker = api.newSpeaker();
        assertEquals(400, api.post("/talks", talk("T", "presentation", "BEGINNER", speaker)).statusCode());
        assertEquals(400, api.post("/talks", talk("T", "Presentation", "BEGINNER", speaker)).statusCode());
        assertEquals(400, api.post("/talks", talk("T", "KEYNOTE", "BEGINNER", speaker)).statusCode());
        assertEquals(400, api.post("/talks", talk("T", "PRESENTATION", "beginner", speaker)).statusCode());
        assertEquals(400, api.post("/talks", talk("T", "PRESENTATION", "EXPERT", speaker)).statusCode());
        assertEquals(400, api.post("/talks", talk("  ", "PRESENTATION", "BEGINNER", speaker)).statusCode());
        assertEquals(400, api.post("/talks", "{\"format\":\"PRESENTATION\",\"level\":\"BEGINNER\",\"speakerId\":" + speaker + "}").statusCode());
        assertEquals(400, api.post("/talks", "{\"title\":\"T\",\"level\":\"BEGINNER\",\"speakerId\":" + speaker + "}").statusCode());
        assertEquals(400, api.post("/talks", "{\"title\":\"T\",\"format\":\"PRESENTATION\",\"speakerId\":" + speaker + "}").statusCode());
        assertEquals(400, api.post("/talks", "{\"title\":\"T\",\"format\":\"PRESENTATION\",\"level\":\"BEGINNER\"}").statusCode());
        // An unknown speaker is 400 today, and the harness accepts that; pinned so a change is deliberate.
        assertEquals(400, api.post("/talks", talk("T", "PRESENTATION", "BEGINNER", 987654321L)).statusCode());
        assertEquals("[]", api.get("/speakers/" + speaker + "/talks").body());
    }

    @Test
    void theFourthTalkOfASpeakerIsRefused() {
        long speaker = api.newSpeaker();
        for (int i = 0; i < 3; i++) {
            api.newTalk(speaker, "PRESENTATION");
        }
        assertEquals(409, api.post("/talks", talk("Fourth", "PRESENTATION", "BEGINNER", speaker)).statusCode());
    }

    @Test
    void aSpeakersTalksAreListedOldestFirst() {
        long speaker = api.newSpeaker();
        long other = api.newSpeaker();
        long first = api.newTalk(speaker, "WORKSHOP").get("id").asLong();
        api.newTalk(other, "WORKSHOP");
        long second = api.newTalk(speaker, "LIGHTNING_10").get("id").asLong();
        long third = api.newTalk(speaker, "PRESENTATION").get("id").asLong();

        List<Long> expected = List.of(first, second, third);
        assertEquals(expected, ids(Api.body(api.get("/speakers/" + speaker + "/talks"), 200)));
        assertEquals(expected, ids(Api.body(api.get("/talks?speakerId=" + speaker), 200)));
    }

    @Test
    void anUnknownTalkIsNotFound() {
        assertEquals(404, api.get("/talks/987654321").statusCode());
        assertEquals(404, api.get("/speakers/987654321/talks").statusCode());
    }

    private static final Pattern SUBMITTED_AT =
            Pattern.compile("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{3}\\+00:00");

    private static String talk(String title, String format, String level, long speakerId) {
        return "{\"title\":\"" + title + "\",\"format\":\"" + format + "\",\"level\":\"" + level
                + "\",\"speakerId\":" + speakerId + "}";
    }

    private static List<Long> ids(JsonNode talks) {
        List<Long> ids = new ArrayList<>();
        talks.forEach(t -> ids.add(t.get("id").asLong()));
        return ids;
    }

    @Test
    void concurrentSubmissionsNeverExceedThreeTalks() throws Exception {
        String speaker = newSpeaker();
        List<CompletableFuture<HttpResponse<String>>> sent = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            sent.add(http.sendAsync(request("POST", "/talks",
                    "{\"title\":\"Talk " + i + "\",\"format\":\"PRESENTATION\",\"level\":\"BEGINNER\",\"speakerId\":" + speaker + "}"),
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
        assertEquals(3, created);
        HttpResponse<String> talks = http.send(request("GET", "/speakers/" + speaker + "/talks", null),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(3, count(talks.body()));
    }

    private String newSpeaker() throws Exception {
        HttpResponse<String> res = http.send(request("POST", "/speakers",
                "{\"name\":\"Grace Hopper\",\"email\":\"grace-" + UUID.randomUUID() + "@example.com\"}"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, res.statusCode(), res.body());
        Matcher m = ID.matcher(res.body());
        assertTrue(m.find(), res.body());
        return m.group(1);
    }

    private static int count(String body) {
        Matcher m = ID.matcher(body);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    private HttpRequest request(String method, String path, String json) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json))
                .build();
    }
}
