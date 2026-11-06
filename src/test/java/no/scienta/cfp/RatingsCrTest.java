package no.scienta.cfp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * The ratings change request (specs/ratings-cr/spec.md): at 5+ ratings the average leaves out one highest and one
 * lowest score, it is rounded to 2 decimals half even, ratingCount still counts everyone, and the ranking uses the
 * new average. Every worked example is checked on GET /talks/{id} and on every list path (which must agree, D2).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RatingsCrTest {

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

    // R2, R3: fewer than 5 ratings, every score counts; 2 decimals.
    @Test
    void underFiveRatingsEveryScoreCountsRoundedToTwoDecimals() {
        assertAverage(rated(5), 5.0, 1);
        assertAverage(rated(4, 4, 4, 5), 4.25, 4);
        assertAverage(rated(4, 4, 4, 1), 3.25, 4);
        assertAverage(rated(4, 4, 5), 4.33, 3);
        assertAverage(rated(4, 5, 5), 4.67, 3);
        assertAverage(rated(4, 5), 4.5, 2);
    }

    // R1, R4: exactly 5 ratings drops one lowest and one highest; ratingCount still 5.
    @Test
    void exactlyFiveRatingsLeaveOutOneLowestAndOneHighest() {
        assertAverage(rated(1, 4, 4, 4, 5), 4.0, 5);
    }

    // R1, D3: ties at the top and bottom still drop exactly one of each: 5+5+1 / 3.
    @Test
    void onlyOneOfSeveralEqualTopAndBottomScoresIsLeftOut() {
        long talk = rated(5, 5, 5, 1, 1);
        assertAverage(talk, 3.67, 5);
        assertTrue(api.get("/talks/" + talk).body().contains("\"averageRating\":3.67,")); // D5: shortest number form
    }

    // R1, D4: all equal at 5+.
    @Test
    void allEqualScoresAtFiveOrMore() {
        assertAverage(rated(3, 3, 3, 3, 3), 3.0, 5);
    }

    // R1, R2: the 5th rating switches trimming on.
    @Test
    void theFifthRatingSwitchesTrimmingOn() {
        long talk = rated(1, 5, 5, 5);
        assertAverage(talk, 4.0, 4);
        api.rate(talk, 5, 5);
        assertAverage(talk, 5.0, 5);
    }

    // D7, R4: re-rating changes which score is the minimum; the count stays.
    @Test
    void reRatingCanChangeTheLeftOutScores() {
        long talk = rated(1, 2, 3, 4, 5);
        assertAverage(talk, 3.0, 5);
        api.rate(talk, 1, 5); // reviewer 1 gave the 1
        assertAverage(talk, 4.0, 5);
    }

    // R1: six ratings, only one of the 4s is left out as the minimum.
    @Test
    void sixRatings() {
        assertAverage(rated(5, 4, 4, 4, 4, 4), 4.0, 6);
    }

    // R3, D1: banker's rounding at a tie on the third decimal.
    @Test
    void tiesOnTheThirdDecimalRoundToEven() {
        long down = rated(1, 1, 3, 3, 3, 3, 4, 4, 4, 5); // 25/8 = 3.125
        assertAverage(down, 3.12, 10);
        assertTrue(api.get("/talks/" + down).body().contains("\"averageRating\":3.12,"));
        assertAverage(rated(1, 3, 3, 3, 3, 3, 4, 4, 4, 5), 3.38, 10); // 27/8 = 3.375
    }

    // R1, R4: twenty reviewers, score r % 5 + 1: 18 kept, sum 54.
    @Test
    void twentyReviewers() {
        int[] scores = new int[20];
        for (int r = 1; r <= 20; r++) {
            scores[r - 1] = r % 5 + 1;
        }
        assertAverage(rated(scores), 3.0, 20);
    }

    // R5, D6: the ranking uses the new rounded average; tie-breaks unchanged.
    @Test
    void theRankingUsesTheNewAverage() {
        long trimmedFive = rated(1, 5, 5, 5, 5);      // 5.0 (4.2 untrimmed), 5 ratings
        long fourPointSevenFive = rated(4, 5, 5, 5);  // 4.75, 4 ratings
        long fourPointThreeThree = rated(4, 4, 5);    // 4.33
        long fourPointTwoFive = rated(4, 4, 4, 5);    // 4.25, more ratings but lower average
        long fourTrimmed = rated(1, 4, 4, 4, 5);      // 4.0 from 5 ratings
        long fourTwice = rated(4, 4);                 // 4.0 from 2
        long fourTwiceLater = rated(4, 4);            // 4.0 from 2, newer
        long unrated = api.newTalk(api.newSpeaker(), "PRESENTATION").get("id").asLong();

        List<Long> ours = List.of(unrated, fourTwiceLater, fourTwice, fourTrimmed, fourPointTwoFive,
                fourPointThreeThree, fourPointSevenFive, trimmedFive);
        List<Long> ranked = new ArrayList<>();
        for (JsonNode t : Api.body(api.get("/talks?sort=rating"), 200)) {
            if (ours.contains(t.get("id").asLong())) {
                ranked.add(t.get("id").asLong());
            }
        }
        assertEquals(List.of(trimmedFive, fourPointSevenFive, fourPointThreeThree, fourPointTwoFive, fourTrimmed,
                fourTwice, fourTwiceLater, unrated), ranked);
    }

    /** A new talk (own speaker) rated by reviewers 1, 2, ... with the given scores. */
    private long rated(int... scores) {
        long talk = api.newTalk(api.newSpeaker(), "PRESENTATION").get("id").asLong();
        for (int i = 0; i < scores.length; i++) {
            api.rate(talk, i + 1, scores[i]);
        }
        return talk;
    }

    /** R7, D2: GET /talks/{id} has the average and count, and every list showing the talk shows exactly the same. */
    private void assertAverage(long talk, double average, int count) {
        JsonNode single = Api.body(api.get("/talks/" + talk), 200);
        assertEquals(average, single.get("averageRating").asDouble(), single.toString());
        assertEquals(count, single.get("ratingCount").asInt(), single.toString());
        long speaker = single.get("speakerId").asLong();
        for (String path : List.of("/talks", "/talks?sort=rating", "/talks?speakerId=" + speaker,
                "/talks?speakerId=" + speaker + "&sort=rating", "/speakers/" + speaker + "/talks")) {
            int found = 0;
            for (JsonNode t : Api.body(api.get(path), 200)) {
                if (t.get("id").asLong() == talk) {
                    assertEquals(single, t, path);
                    found++;
                }
            }
            assertEquals(1, found, path);
        }
    }
}
