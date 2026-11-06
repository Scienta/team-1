package no.scienta.cfp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Ratings (specs/ratings/spec.md): a score is a whole number 1-5 from a positive reviewer id, one rating per reviewer
 * and talk, the average is rounded half up to one decimal and shown wherever a talk is, the ranking orders by the
 * rounded average, then the number of ratings, then age, with unrated talks last, and concurrent ratings are neither
 * lost nor counted twice. Runs the real app on a random port with a throwaway database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RatingsTest {

    private static final Pattern RATED_AT_DISPLAY = Pattern.compile("\\d{1,2}/\\d{1,2}/\\d\\d, \\d{1,2}:\\d\\d (AM|PM)");

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

    @Test
    void aRatingIsReturnedWithTheLegacyDisplayTimeInUtc() {
        long talk = newTalk();
        ZonedDateTime before = ZonedDateTime.now(ZoneOffset.UTC);
        JsonNode rating = api.rate(talk, 42, 4);
        ZonedDateTime after = ZonedDateTime.now(ZoneOffset.UTC);

        assertEquals(talk, rating.get("talkId").asLong());
        assertEquals(42, rating.get("reviewerId").asLong());
        assertEquals(4, rating.get("score").asInt());
        String shown = rating.get("ratedAtDisplay").asString();
        assertTrue(RATED_AT_DISPLAY.matcher(shown).matches(), "not M/d/yy, h:mm AM with an ordinary space: " + shown);
        DateTimeFormatter usShort = DateTimeFormatter.ofPattern("M/d/yy, h:mm a", Locale.US);
        assertTrue(shown.equals(usShort.format(before)) || shown.equals(usShort.format(after)),
                shown + " is not the current UTC time");
    }

    @Test
    void onlyWholeScoresFromOneToFiveFromPositiveReviewersCount() {
        long talk = newTalk();
        for (String body : List.of("{\"score\":0}", "{\"score\":6}", "{\"score\":-1}", "{\"score\":3.5}",
                "{\"score\":\"3\"}", "{\"score\":null}", "{}")) {
            assertEquals(400, api.put("/talks/" + talk + "/ratings/1", body).statusCode(), body);
        }
        assertEquals(400, api.put("/talks/" + talk + "/ratings/0", "{\"score\":3}").statusCode());
        assertEquals(400, api.put("/talks/" + talk + "/ratings/-1", "{\"score\":3}").statusCode());
        assertEquals(400, api.put("/talks/" + talk + "/ratings/abc", "{\"score\":3}").statusCode());
        assertEquals(404, api.put("/talks/987654321/ratings/1", "{\"score\":3}").statusCode());

        JsonNode shown = Api.body(api.get("/talks/" + talk), 200);
        assertEquals(0, shown.get("ratingCount").asInt(), shown.toString());
    }

    @Test
    void ratingAgainReplacesTheReviewersScore() {
        long talk = newTalk();
        api.rate(talk, 7, 2);
        api.rate(talk, 7, 5);
        JsonNode shown = Api.body(api.get("/talks/" + talk), 200);
        assertEquals(1, shown.get("ratingCount").asInt());
        assertEquals(5.0, shown.get("averageRating").asDouble());
    }

    @Test
    void theAverageIsRoundedToTwoDecimals() {
        long talk = newTalk();
        api.rate(talk, 1, 4);
        api.rate(talk, 2, 4);
        api.rate(talk, 3, 4);
        api.rate(talk, 4, 5);
        JsonNode shown = Api.body(api.get("/talks/" + talk), 200);
        assertEquals(4, shown.get("ratingCount").asInt());
        assertEquals("4.25", shown.get("averageRating").asString()); // ratings-cr R3: was 4.3
    }

    @Test
    void anUnratedTalkHasNoAverageWhereverItIsShown() {
        long speaker = api.newSpeaker();
        long talk = api.newTalk(speaker, "WORKSHOP").get("id").asLong();

        List<JsonNode> shown = new ArrayList<>();
        shown.add(Api.body(api.get("/talks/" + talk), 200));
        shown.add(Api.body(api.get("/talks?speakerId=" + speaker), 200).get(0));
        shown.add(Api.body(api.get("/speakers/" + speaker + "/talks"), 200).get(0));
        for (JsonNode t : find(Api.body(api.get("/talks"), 200), talk)) {
            shown.add(t);
        }
        assertEquals(4, shown.size());
        for (JsonNode t : shown) {
            assertTrue(t.has("averageRating") && t.get("averageRating").isNull(), t.toString());
            assertEquals(0, t.get("ratingCount").asInt(), t.toString());
        }
    }

    @Test
    void theRankingIsByRoundedAverageThenRatingCountThenAgeWithUnratedLast() {
        long unrated = newTalk();
        long fourTwice = newTalk();
        long fourOnce = newTalk();
        long fourAndAHalf = newTalk();
        long fourTwiceLater = newTalk();
        long fourPointTwoFive = newTalk();
        long fourPointThreeThree = newTalk();

        rateAll(fourTwice, 4, 4);
        rateAll(fourOnce, 4);
        rateAll(fourAndAHalf, 5, 4);
        rateAll(fourTwiceLater, 4, 4);
        rateAll(fourPointTwoFive, 4, 4, 4, 5);   // 4.25, 4 ratings (ratings-cr: no longer ties with 4.33)
        rateAll(fourPointThreeThree, 4, 4, 5);   // 4.33, 3 ratings

        List<Long> ours = List.of(unrated, fourTwice, fourOnce, fourAndAHalf, fourTwiceLater, fourPointTwoFive,
                fourPointThreeThree);
        List<Long> ranked = new ArrayList<>();
        for (JsonNode t : Api.body(api.get("/talks?sort=rating"), 200)) {
            if (ours.contains(t.get("id").asLong())) {
                ranked.add(t.get("id").asLong());
            }
        }
        assertEquals(List.of(fourAndAHalf, fourPointThreeThree, fourPointTwoFive, fourTwice, fourTwiceLater, fourOnce,
                unrated), ranked);

        boolean seenUnrated = false;
        for (JsonNode t : Api.body(api.get("/talks?sort=rating"), 200)) {
            boolean isUnrated = t.get("averageRating").isNull();
            assertTrue(isUnrated || !seenUnrated, "rated talk " + t.get("id") + " after an unrated one");
            seenUnrated |= isUnrated;
        }
        assertEquals(400, api.get("/talks?sort=score").statusCode());
    }

    @Test
    void manyReviewersAtOnceAreAllCounted() {
        long talk = newTalk();
        List<CompletableFuture<HttpResponse<String>>> sent = new ArrayList<>();
        for (int reviewer = 1; reviewer <= 20; reviewer++) {
            sent.add(api.sendAsync("PUT", "/talks/" + talk + "/ratings/" + reviewer, "{\"score\":" + (reviewer % 5 + 1) + "}"));
        }
        for (CompletableFuture<HttpResponse<String>> f : sent) {
            HttpResponse<String> res = f.join();
            assertEquals(200, res.statusCode(), res.body());
        }
        JsonNode shown = Api.body(api.get("/talks/" + talk), 200);
        assertEquals(20, shown.get("ratingCount").asInt());
        assertEquals(3.0, shown.get("averageRating").asDouble());
    }

    @Test
    void aDoubleClickedRatingIsCountedOnce() {
        long talk = newTalk();
        List<CompletableFuture<HttpResponse<String>>> sent = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            sent.add(api.sendAsync("PUT", "/talks/" + talk + "/ratings/99", "{\"score\":3}"));
        }
        for (CompletableFuture<HttpResponse<String>> f : sent) {
            HttpResponse<String> res = f.join();
            assertEquals(200, res.statusCode(), res.body());
        }
        JsonNode shown = Api.body(api.get("/talks/" + talk), 200);
        assertEquals(1, shown.get("ratingCount").asInt());
        assertEquals(3.0, shown.get("averageRating").asDouble());
    }

    private long newTalk() {
        return api.newTalk(api.newSpeaker(), "PRESENTATION").get("id").asLong();
    }

    private void rateAll(long talk, int... scores) {
        for (int i = 0; i < scores.length; i++) {
            api.rate(talk, 1000 + i, scores[i]);
        }
    }

    private static List<JsonNode> find(JsonNode talks, long id) {
        List<JsonNode> found = new ArrayList<>();
        talks.forEach(t -> {
            if (t.get("id").asLong() == id) {
                found.add(t);
            }
        });
        return found;
    }
}
