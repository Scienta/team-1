package no.scienta.cfp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * The talk lists load ratings and co-speakers for all talks at once (specs/talk-list-performance). Every talk in a
 * list must still be exactly what GET /talks/{id} says, which reads them one talk at a time: rating count and
 * average (ratings-cr: trimmed at 5+, 2 decimals half even, null when unrated), co-speakers in the order they were added, and the sort=rating
 * order. /admin/stats and /admin/talks.csv must agree too.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TalkListBatchTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.fresh(registry);
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void listsAgreeWithTheSingleTalkView() {
        Api api = new Api(port);
        long s1 = api.newSpeaker();
        long s2 = api.newSpeaker();
        long s3 = api.newSpeaker();
        long a = api.newSpeaker();
        long b = api.newSpeaker();
        api.newSpeaker(); // a speaker without talks

        long unrated = api.newTalk(s1, "PRESENTATION").get("id").asLong();      // 0 ratings, 0 co-speakers
        long one = api.newTalk(s1, "WORKSHOP").get("id").asLong();              // 1 rating, 1 co-speaker
        long three = api.newTalk(s1, "LIGHTNING_10").get("id").asLong();        // 3 ratings, 2 co-speakers
        long halfUp = api.newTalk(s2, "PRESENTATION").get("id").asLong();       // 4.25 (2 decimals, ratings-cr)
        long tieFew = api.newTalk(s2, "LIGHTNING_20").get("id").asLong();       // 4.5 from 2 ratings
        long tieMany = api.newTalk(s3, "PRESENTATION").get("id").asLong();      // 4.5 from 4 ratings, before tieFew
        long tieSameA = api.newTalk(s3, "PRESENTATION").get("id").asLong();     // 3.0 from 1, tie on everything
        long tieSameB = api.newTalk(s3, "PRESENTATION").get("id").asLong();     // ... but the id

        api.rate(one, 1, 4);
        api.put("/talks/" + one + "/co-speakers/" + a, null);
        api.rate(three, 1, 4);
        api.rate(three, 2, 4);
        api.rate(three, 3, 5);                                                   // 13/3 = 4.333 -> 4.33
        api.put("/talks/" + three + "/co-speakers/" + b, null);                 // b before a: order added, not id
        api.put("/talks/" + three + "/co-speakers/" + a, null);
        for (int[] r : new int[][] {{1, 5}, {2, 4}, {3, 4}, {4, 4}}) {
            api.rate(halfUp, r[0], r[1]);
        }
        api.rate(tieFew, 1, 4);
        api.rate(tieFew, 2, 5);
        for (int[] r : new int[][] {{1, 5}, {2, 5}, {3, 4}, {4, 4}}) {
            api.rate(tieMany, r[0], r[1]);
        }
        api.rate(tieSameB, 7, 3);
        api.rate(tieSameA, 7, 3);
        api.rate(tieSameA, 7, 3); // re-rating replaces, still one

        Map<Long, JsonNode> single = new LinkedHashMap<>();
        for (long id : new long[] {unrated, one, three, halfUp, tieFew, tieMany, tieSameA, tieSameB}) {
            single.put(id, Api.body(api.get("/talks/" + id), 200));
        }
        // The single-talk view itself, so a mistake in both paths can't pass.
        assertTrue(single.get(unrated).get("averageRating").isNull());
        assertEquals(0, single.get(unrated).get("ratingCount").asInt());
        assertEquals("[]", single.get(unrated).get("coSpeakerIds").toString());
        assertEquals(4.0, single.get(one).get("averageRating").asDouble());
        assertEquals("[" + a + "]", single.get(one).get("coSpeakerIds").toString());
        assertEquals(4.33, single.get(three).get("averageRating").asDouble());
        assertEquals("[" + b + "," + a + "]", single.get(three).get("coSpeakerIds").toString());
        assertEquals(4.25, single.get(halfUp).get("averageRating").asDouble());
        assertEquals(1, single.get(tieSameA).get("ratingCount").asInt());

        // GET /talks: every talk, by id, each equal to its GET /talks/{id}
        JsonNode all = Api.body(api.get("/talks"), 200);
        assertEquals(new ArrayList<>(single.keySet()), ids(all));
        for (JsonNode t : all) {
            assertEquals(single.get(t.get("id").asLong()), t);
        }

        // GET /talks?sort=rating: average desc, count desc, id asc, then the unrated by id
        JsonNode byRating = Api.body(api.get("/talks?sort=rating"), 200);
        assertEquals(List.of(tieMany, tieFew, three, halfUp, one, tieSameA, tieSameB, unrated), ids(byRating));
        for (JsonNode t : byRating) {
            assertEquals(single.get(t.get("id").asLong()), t);
        }

        // GET /speakers/{id}/talks and GET /talks?speakerId=
        for (long speaker : new long[] {s1, s2, s3}) {
            for (String path : new String[] {"/speakers/" + speaker + "/talks", "/talks?speakerId=" + speaker}) {
                JsonNode list = Api.body(api.get(path), 200);
                assertEquals(3 - (speaker == s2 ? 1 : 0), list.size(), path);
                for (JsonNode t : list) {
                    assertEquals(speaker, t.get("speakerId").asLong());
                    assertEquals(single.get(t.get("id").asLong()), t, path);
                }
            }
        }

        // /admin/stats: mean of the rated talks' averages, 2 decimals half even (ratings-cr D8)
        JsonNode stats = Api.body(api.get("/admin/stats"), 200);
        assertEquals(6, stats.get("speakers").asInt());
        assertEquals(3, stats.get("speakersWithTalks").asInt());
        assertEquals(8, stats.get("talks").asInt());
        assertEquals(5, stats.get("byFormat").get("PRESENTATION").asInt());
        // (4.0 + 4.33 + 4.25 + 4.5 + 4.5 + 3.0 + 3.0) / 7 = 27.58 / 7 = 3.94
        assertEquals(3.94, stats.get("averageRating").asDouble());

        // /admin/talks.csv: rating with a decimal comma, count, speaker name
        String csv = api.get("/admin/talks.csv").body();
        String[] rows = csv.split("\n");
        assertEquals(9, rows.length);
        assertTrue(rows[1].startsWith(unrated + ";"));
        assertTrue(rows[1].contains(";Grace Hopper;SUBMITTED;") && rows[1].endsWith(";;0"), rows[1]);
        assertTrue(rows[3].startsWith(three + ";") && rows[3].endsWith(";4,33;3"), rows[3]);

        // A talk whose speaker row is gone (old data): not a speaker with talks, and the CSV is 404 as before.
        jdbc.update("INSERT INTO talk (title, format, duration, level, status, speaker_id, submitted_at) "
                + "VALUES ('Orphan', 'PRESENTATION', 45, 'BEGINNER', 'SUBMITTED', 999999, 0)");
        stats = Api.body(api.get("/admin/stats"), 200);
        assertEquals(3, stats.get("speakersWithTalks").asInt());
        assertEquals(9, stats.get("talks").asInt());
        assertEquals(404, api.get("/admin/talks.csv").statusCode());
    }

    private static List<Long> ids(JsonNode list) {
        List<Long> ids = new ArrayList<>();
        for (JsonNode t : list) {
            ids.add(t.get("id").asLong());
        }
        return ids;
    }
}
