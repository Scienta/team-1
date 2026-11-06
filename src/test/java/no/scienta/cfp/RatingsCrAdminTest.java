package no.scienta.cfp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * The ratings change request in the admin reports (specs/ratings-cr/spec.md D8, D9), on its own database so the
 * statistics only see these talks.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RatingsCrAdminTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.fresh(registry);
    }

    @Value("${local.server.port}")
    int port;

    @Test
    void adminStatsAndCsvUseTheNewAverage() {
        Api api = new Api(port);
        long speaker = api.newSpeaker();
        long unrated = api.newTalk(speaker, "PRESENTATION").get("id").asLong();
        long fourPointTwoFive = api.newTalk(speaker, "PRESENTATION").get("id").asLong();
        long trimmed = api.newTalk(speaker, "PRESENTATION").get("id").asLong();
        rate(api, fourPointTwoFive, 4, 4, 4, 5);  // 4.25
        rate(api, trimmed, 1, 4, 4, 4, 5);        // 4.0 (trimmed)

        // D8: (4.25 + 4.0) / 2 = 4.125 -> 4.12 half even (4.1 cut off, 4.13 half up); the unrated talk doesn't count
        JsonNode stats = Api.body(api.get("/admin/stats"), 200);
        assertEquals(4.12, stats.get("averageRating").asDouble(), stats.toString());

        // D9: the CSV shows the same value with a decimal comma, and every reviewer in the count
        String[] rows = api.get("/admin/talks.csv").body().split("\n");
        assertEquals(4, rows.length);
        assertTrue(rows[1].startsWith(unrated + ";") && rows[1].endsWith(";;0"), rows[1]);
        assertTrue(rows[2].startsWith(fourPointTwoFive + ";") && rows[2].endsWith(";4,25;4"), rows[2]);
        assertTrue(rows[3].startsWith(trimmed + ";") && rows[3].endsWith(";4,0;5"), rows[3]);

        // D8: (4.25 + 4.0 + 3.67) / 3 = 3.9733 -> 3.97
        long speaker2 = api.newSpeaker();
        long ties = api.newTalk(speaker2, "PRESENTATION").get("id").asLong();
        rate(api, ties, 5, 5, 5, 1, 1);           // 3.67
        stats = Api.body(api.get("/admin/stats"), 200);
        assertEquals(3.97, stats.get("averageRating").asDouble(), stats.toString());
        rows = api.get("/admin/talks.csv").body().split("\n");
        assertTrue(rows[4].startsWith(ties + ";") && rows[4].endsWith(";3,67;5"), rows[4]);
    }

    private static void rate(Api api, long talk, int... scores) {
        for (int i = 0; i < scores.length; i++) {
            api.rate(talk, i + 1, scores[i]);
        }
    }
}
