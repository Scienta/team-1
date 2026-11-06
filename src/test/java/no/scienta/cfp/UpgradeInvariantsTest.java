package no.scienta.cfp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.SpringVersion;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The invariants of the Spring Boot upgrade (specs/spring-boot-4/spec.md) that the base suites didn't pin yet: the
 * exact JSON formats, field names and order, null handling, framework-produced status codes and error bodies, JSON
 * comments, and the running versions on /actuator/info. Runs the real app on a random port with a throwaway database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UpgradeInvariantsTest {

    private static final Pattern SUBMITTED_ON = Pattern.compile("\\d{4}-\\d\\d-\\d\\d");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.fresh(registry);
    }

    @Value("${local.server.port}")
    int port;

    private Api api;

    @BeforeEach
    void api() {
        api = new Api(port);
    }

    // R4, R5, D4, D13: both timestamps are JSON strings; submittedOn is exactly yyyy-MM-dd, the UTC date of submittedAt.
    @Test
    void submittedOnIsExactlyTheUtcDateOfSubmittedAt() {
        JsonNode talk = api.newTalk(api.newSpeaker(), "PRESENTATION");
        assertTrue(talk.get("submittedAt").isTextual(), talk.toString());
        assertTrue(talk.get("submittedOn").isTextual(), talk.toString());
        String submittedOn = talk.get("submittedOn").asText();
        assertTrue(SUBMITTED_ON.matcher(submittedOn).matches(), submittedOn);
        OffsetDateTime submittedAt = OffsetDateTime.parse(talk.get("submittedAt").asText());
        assertEquals(submittedAt.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate().toString(), submittedOn);
    }

    // R7, D13: nulls are written, not dropped.
    @Test
    void nullsAreWrittenNotDropped() {
        long speaker = api.newSpeaker();
        HttpResponse<String> s = api.get("/speakers/" + speaker);
        assertTrue(s.body().contains("\"bio\":null"), s.body());
        JsonNode talk = api.newTalk(speaker, "WORKSHOP");
        HttpResponse<String> t = api.get("/talks/" + talk.get("id").asLong());
        assertTrue(t.body().contains("\"averageRating\":null"), t.body());
    }

    // R7: ids are integers, averageRating is a JSON number with one decimal.
    @Test
    void numberTypesAreUnchanged() {
        long speaker = api.newSpeaker();
        long talk = api.newTalk(speaker, "WORKSHOP").get("id").asLong();
        api.rate(talk, 1, 4);
        api.rate(talk, 2, 5);
        JsonNode shown = Api.body(api.get("/talks/" + talk), 200);
        assertTrue(shown.get("id").isIntegralNumber(), shown.toString());
        assertTrue(shown.get("speakerId").isIntegralNumber(), shown.toString());
        assertTrue(shown.get("averageRating").isNumber(), shown.toString());
        assertTrue(api.get("/talks/" + talk).body().contains("\"averageRating\":4.5,"));
        assertTrue(Api.body(api.get("/speakers/" + speaker), 200).get("id").isIntegralNumber());
    }

    // R8, D8: field names, in today's order.
    @Test
    void fieldNamesAndOrderAreUnchanged() {
        long speaker = api.newSpeaker();
        assertEquals(List.of("id", "name", "email", "bio"), names(Api.body(api.get("/speakers/" + speaker), 200)));
        JsonNode talk = api.newTalk(speaker, "PRESENTATION");
        List<String> talkFields = List.of("id", "title", "format", "level", "speakerId", "durationMinutes", "status",
                "submittedAt", "submittedOn", "averageRating", "ratingCount", "coSpeakerIds");
        assertEquals(talkFields, names(talk));
        assertEquals(talkFields, names(Api.body(api.get("/talks/" + talk.get("id").asLong()), 200)));
        assertEquals(List.of("talkId", "reviewerId", "score", "ratedAtDisplay"),
                names(api.rate(talk.get("id").asLong(), 3, 3)));
        assertEquals(List.of("status", "version"), names(Api.body(api.get("/health"), 200)));
    }

    // R9, D9, D13: malformed JSON is 400, wherever a body is read.
    @Test
    void malformedJsonIsBadRequest() {
        long speaker = api.newSpeaker();
        long talk = api.newTalk(speaker, "PRESENTATION").get("id").asLong();
        assertEquals(400, api.post("/speakers", "{\"name\":\"Ada\",").statusCode());
        assertEquals(400, api.post("/talks", "{not json").statusCode());
        assertEquals(400, api.put("/talks/" + talk + "/ratings/1", "{\"score\":").statusCode());
        assertEquals(400, api.put("/speakers/" + speaker, "nope").statusCode());
    }

    // R9: a non-numeric path id is 400, not 404 or 500.
    @Test
    void aNonNumericPathIdIsBadRequest() {
        assertEquals(400, api.get("/talks/abc").statusCode());
        assertEquals(400, api.get("/speakers/abc").statusCode());
        assertEquals(400, api.get("/speakers/abc/talks").statusCode());
    }

    // R12: error bodies keep Boot's shape, with the message filled in.
    @Test
    void errorBodiesKeepBootsShapeWithTheMessage() {
        HttpResponse<String> res = api.get("/talks/987654321");
        JsonNode error = Api.body(res, 404);
        assertEquals(List.of("timestamp", "status", "error", "message", "path"), names(error));
        assertEquals(404, error.get("status").asInt());
        assertEquals("Not Found", error.get("error").asText());
        assertEquals("talk not found", error.get("message").asText());
        assertEquals("/talks/987654321", error.get("path").asText());
        assertTrue(res.headers().firstValue("Content-Type").orElse("").startsWith("application/json"), res.headers().toString());

        JsonNode badRequest = Api.body(api.post("/talks", "{\"title\":\"T\",\"format\":\"KEYNOTE\",\"level\":\"BEGINNER\",\"speakerId\":1}"), 400);
        assertEquals(List.of("timestamp", "status", "error", "message", "path"), names(badRequest));
        assertEquals("Bad Request", badRequest.get("error").asText());
        assertTrue(badRequest.get("message").asText().startsWith("format must be one of"), badRequest.toString());

        JsonNode malformed = Api.body(api.post("/talks", "{not json"), 400);
        assertEquals(400, malformed.get("status").asInt());
        assertEquals("/talks", malformed.get("path").asText());
        assertTrue(malformed.has("timestamp") && malformed.has("error") && malformed.has("message"), malformed.toString());
    }

    // D7: comments in request bodies are still accepted.
    @Test
    void jsonCommentsInRequestBodiesAreAccepted() {
        String json = "{ /* mobile client */ \"name\":\"Ada\", // trailing\n \"email\":\"c-" + UUID.randomUUID() + "@example.com\"}";
        assertEquals(201, api.post("/speakers", json).statusCode());
    }

    // R14: the admin exports answer as before.
    @Test
    void theAdminExportsAnswer() {
        api.newTalk(api.newSpeaker(), "PRESENTATION");
        JsonNode stats = Api.body(api.get("/admin/stats"), 200);
        assertEquals(List.of("speakers", "speakersWithTalks", "talks", "byFormat", "byLevel", "byStatus", "totalHours",
                "averageRating", "generated"), names(stats));
        HttpResponse<String> csv = api.get("/admin/talks.csv");
        assertEquals(200, csv.statusCode());
        assertEquals("text/csv;charset=UTF-8", csv.headers().firstValue("Content-Type").orElse("").replace(" ", ""));
        assertTrue(csv.body().startsWith("id;title;format;minutes;level;speaker;status;submitted;rating;ratings\n"), csv.body());
    }

    // R3, D13: /actuator/info reports the versions this process actually runs on.
    @Test
    void actuatorInfoReportsTheRunningVersions() {
        JsonNode versions = Api.body(api.get("/actuator/info"), 200).get("versions");
        assertEquals(List.of("java", "springBoot", "springFramework"), names(versions));
        assertEquals(System.getProperty("java.version"), versions.get("java").asText());
        assertEquals(SpringBootVersion.getVersion(), versions.get("springBoot").asText());
        assertEquals(SpringVersion.getVersion(), versions.get("springFramework").asText());
    }

    private static List<String> names(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
