package no.scienta.cfp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A talk has at most two co-speakers, in the order they were added, and co-speaking doesn't count towards the limit of
 * 3 talks.
 *
 * Status codes (the feature request doesn't give them; these are ours, checked against the harness):
 * re-adding a co-speaker is 200 and changes nothing; a third co-speaker is 409; the main speaker as their own
 * co-speaker is 400 (409 failed co-speakers/check-2); removing someone who isn't a co-speaker, also removing twice,
 * is 404 (200 failed co-speakers/check-1); an unknown talk or speaker is 404.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CoSpeakersTest {

    private static final Pattern ID = Pattern.compile("\"id\":(\\d+)");
    private static final Pattern CO_SPEAKERS = Pattern.compile("\"coSpeakerIds\":\\[([0-9,]*)]");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.fresh(registry);
    }

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void addKeepsOrderIsIdempotentAndCapsAtTwo() throws Exception {
        String main = newSpeaker();
        String a = newSpeaker();
        String b = newSpeaker();
        String c = newSpeaker();
        String talk = newTalk(main);

        assertEquals("", coSpeakers(send("GET", "/talks/" + talk, 200)));
        assertEquals(b, coSpeakers(send("PUT", "/talks/" + talk + "/co-speakers/" + b, 200)));
        assertEquals(b + "," + a, coSpeakers(send("PUT", "/talks/" + talk + "/co-speakers/" + a, 200)));
        assertEquals(b + "," + a, coSpeakers(send("PUT", "/talks/" + talk + "/co-speakers/" + b, 200)));
        send("PUT", "/talks/" + talk + "/co-speakers/" + c, 409);
        assertEquals(b + "," + a, coSpeakers(send("PUT", "/talks/" + talk + "/co-speakers/" + a, 200)));
        assertEquals(b + "," + a, coSpeakers(send("GET", "/talks/" + talk, 200)));

        assertEquals(a, coSpeakers(send("DELETE", "/talks/" + talk + "/co-speakers/" + b, 200)));
        assertEquals(a + "," + c, coSpeakers(send("PUT", "/talks/" + talk + "/co-speakers/" + c, 200)));
    }

    @Test
    void theMainSpeakerCannotCoSpeakOnTheirOwnTalk() throws Exception {
        String main = newSpeaker();
        String co = newSpeaker();
        String talk = newTalk(main);
        send("PUT", "/talks/" + talk + "/co-speakers/" + co, 200);

        send("PUT", "/talks/" + talk + "/co-speakers/" + main, 400);
        assertEquals(co, coSpeakers(send("GET", "/talks/" + talk, 200)));
    }

    @Test
    void removingSomeoneWhoIsNotACoSpeakerIs404AndChangesNothing() throws Exception {
        String main = newSpeaker();
        String a = newSpeaker();
        String b = newSpeaker();
        String stranger = newSpeaker();
        String talk = newTalk(main);
        send("PUT", "/talks/" + talk + "/co-speakers/" + a, 200);
        send("PUT", "/talks/" + talk + "/co-speakers/" + b, 200);

        assertEquals(a, coSpeakers(send("DELETE", "/talks/" + talk + "/co-speakers/" + b, 200)));
        send("DELETE", "/talks/" + talk + "/co-speakers/" + b, 404);
        send("DELETE", "/talks/" + talk + "/co-speakers/" + stranger, 404);
        send("DELETE", "/talks/" + talk + "/co-speakers/" + main, 404);
        assertEquals(a, coSpeakers(send("GET", "/talks/" + talk, 200)));
    }

    @Test
    void unknownTalkOrSpeakerIs404() throws Exception {
        String main = newSpeaker();
        String talk = newTalk(main);
        send("PUT", "/talks/999999/co-speakers/" + main, 404);
        send("DELETE", "/talks/999999/co-speakers/" + main, 404);
        send("PUT", "/talks/" + talk + "/co-speakers/999999", 404);
        send("DELETE", "/talks/" + talk + "/co-speakers/999999", 404);
    }

    @Test
    void coSpeakingDoesNotCountTowardsTheTalkLimit() throws Exception {
        String busy = newSpeaker();
        newTalk(busy);
        newTalk(busy);
        newTalk(busy);
        String other = newSpeaker();
        String talk = newTalk(other);

        send("PUT", "/talks/" + talk + "/co-speakers/" + busy, 200);
        assertEquals(3, count(send("GET", "/speakers/" + busy + "/talks", 200)));
        assertEquals(3, count(send("GET", "/talks?speakerId=" + busy, 200)));
    }

    @Test
    void coSpeakingOnThreeTalksStillLeavesThreeSubmissions() throws Exception {
        String speaker = newSpeaker();
        for (int i = 0; i < 3; i++) {
            send("PUT", "/talks/" + newTalk(newSpeaker()) + "/co-speakers/" + speaker, 200);
        }
        newTalk(speaker);
        newTalk(speaker);
        newTalk(speaker);
    }

    @Test
    void talkListShowsCoSpeakers() throws Exception {
        String main = newSpeaker();
        String co = newSpeaker();
        String talk = newTalk(main);
        send("PUT", "/talks/" + talk + "/co-speakers/" + co, 200);

        String body = send("GET", "/talks?speakerId=" + main, 200);
        assertEquals(1, count(body));
        assertEquals(co, coSpeakers(body));
    }

    @Test
    void concurrentAddsNeverExceedTwo() throws Exception {
        String talk = newTalk(newSpeaker());
        List<String> speakers = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            speakers.add(newSpeaker());
        }
        List<CompletableFuture<HttpResponse<String>>> sent = new ArrayList<>();
        for (String speaker : speakers) {
            sent.add(http.sendAsync(request("PUT", "/talks/" + talk + "/co-speakers/" + speaker),
                    HttpResponse.BodyHandlers.ofString()));
        }
        int added = 0;
        for (CompletableFuture<HttpResponse<String>> f : sent) {
            HttpResponse<String> res = f.join();
            assertTrue(res.statusCode() == 200 || res.statusCode() == 409, res.statusCode() + " " + res.body());
            if (res.statusCode() == 200) {
                added++;
            }
        }
        assertEquals(2, added);
        assertEquals(2, coSpeakers(send("GET", "/talks/" + talk, 200)).split(",").length);
    }

    private String newSpeaker() throws Exception {
        return id(send("POST", "/speakers",
                "{\"name\":\"Ada Lovelace\",\"email\":\"ada-" + UUID.randomUUID() + "@example.com\"}", 201));
    }

    private String newTalk(String speaker) throws Exception {
        return id(send("POST", "/talks",
                "{\"title\":\"Talk\",\"format\":\"PRESENTATION\",\"level\":\"BEGINNER\",\"speakerId\":" + speaker + "}", 201));
    }

    private String send(String method, String path, int expected) throws Exception {
        return send(method, path, null, expected);
    }

    private String send(String method, String path, String json, int expected) throws Exception {
        HttpResponse<String> res = http.send(json == null ? request(method, path) : request(method, path, json),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(expected, res.statusCode(), method + " " + path + ": " + res.body());
        return res.body();
    }

    private static String id(String body) {
        Matcher m = ID.matcher(body);
        assertTrue(m.find(), body);
        return m.group(1);
    }

    private static String coSpeakers(String body) {
        Matcher m = CO_SPEAKERS.matcher(body);
        assertTrue(m.find(), body);
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

    private HttpRequest request(String method, String path) {
        return request(method, path, null);
    }

    private HttpRequest request(String method, String path, String json) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json))
                .build();
    }
}
