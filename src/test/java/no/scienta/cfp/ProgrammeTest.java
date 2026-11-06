package no.scienta.cfp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Programme scheduling (specs/scheduling/spec.md). Comments name the rule (R) or decision (D) each test proves.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProgrammeTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.fresh(registry);
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    private Api api;

    @BeforeEach
    void api() {
        api = new Api(port);
        // Cells are a fixed venue shared across tests in this class.
        jdbc.update("DELETE FROM programme");
    }

    // ------------------------------------------------------------ helpers

    private long acceptedTalk() {
        return acceptedTalk("PRESENTATION");
    }

    private long acceptedTalk(String format) {
        return acceptedTalkOf(api.newSpeaker(), format);
    }

    private long acceptedTalkOf(long speaker, String format) {
        long id = api.newTalk(speaker, format).get("id").asLong();
        api.rate(id, 1, 4);
        api.rate(id, 2, 5);
        Api.body(api.put("/talks/" + id + "/status", "{\"status\":\"ACCEPTED\"}"), 200);
        return id;
    }

    private HttpResponse<String> put(String room, String slot, long talkId) {
        return api.put("/programme/" + room + "/" + slot, "{\"talkId\":" + talkId + "}");
    }

    private HttpResponse<String> putBody(String room, String slot, String body) {
        return api.put("/programme/" + room + "/" + slot, body);
    }

    private HttpResponse<String> get(String room, String slot) {
        return api.get("/programme/" + room + "/" + slot);
    }

    private HttpResponse<String> delete(String room, String slot) {
        return api.send("DELETE", "/programme/" + room + "/" + slot, null);
    }

    private JsonNode entry(HttpResponse<String> res) {
        return Api.body(res, 200);
    }

    private void assertEntry(JsonNode e, String room, String slot, String start, String end, long talkId) {
        assertEquals(room, e.get("room").asText());
        assertEquals(slot, e.get("slot").asText());
        assertEquals(start, e.get("start").asText());
        assertEquals(end, e.get("end").asText());
        assertEquals(talkId, e.get("talkId").asLong());
    }

    // ------------------------------------------------------------ R2, R1: put / get / delete happy path

    @Test
    void r1r2_putAcceptedTalkReturnsEntryWithFixedTimes() {
        long talk = acceptedTalk();
        JsonNode e = entry(put("R1", "S1", talk)); // R1, R2
        assertEntry(e, "R1", "S1", "09:00", "10:00", talk);
    }

    @Test
    void r2d2_getOccupiedAndEmpty() {
        long talk = acceptedTalk();
        entry(put("R2", "S2", talk));
        assertEntry(entry(get("R2", "S2")), "R2", "S2", "10:15", "11:15", talk); // R2
        assertEquals(404, get("R3", "S2").statusCode()); // D2
    }

    @Test
    void r2d11d12_deleteReturnsRemovedEntryThenGone() {
        long talk = acceptedTalk();
        entry(put("R1", "S3", talk));
        assertEntry(entry(delete("R1", "S3")), "R1", "S3", "11:30", "12:30", talk); // D12
        assertEquals(404, get("R1", "S3").statusCode());
        assertEquals(404, delete("R1", "S3").statusCode()); // D11
    }

    @Test
    void r2_removedTalkCanBePlacedElsewhere() {
        long talk = acceptedTalk();
        entry(put("R1", "S1", talk));
        entry(delete("R1", "S1"));
        assertEntry(entry(put("R3", "S5", talk)), "R3", "S5", "15:45", "16:45", talk);
    }

    @Test
    void d16_trailingSlashIsAccepted() {
        long talk = acceptedTalk();
        JsonNode e = Api.body(api.put("/programme/R1/S1/", "{\"talkId\":" + talk + "}"), 200);
        assertEntry(e, "R1", "S1", "09:00", "10:00", talk);
        assertEntry(Api.body(api.get("/programme/R1/S1/"), 200), "R1", "S1", "09:00", "10:00", talk);
        assertEntry(Api.body(api.send("DELETE", "/programme/R1/S1/", null), 200), "R1", "S1", "09:00", "10:00", talk);
    }

    // ------------------------------------------------------------ D1, D3, D4: invalid path / body / talk

    @Test
    void d1_unknownOrWrongCaseRoomOrSlotIs404() {
        long talk = acceptedTalk();
        assertEquals(404, put("r1", "S1", talk).statusCode());
        assertEquals(404, put("R4", "S1", talk).statusCode());
        assertEquals(404, put("R1", "s1", talk).statusCode());
        assertEquals(404, put("R1", "S6", talk).statusCode());
        assertEquals(404, get("R9", "S1").statusCode());
        assertEquals(404, delete("R1", "SX").statusCode());
    }

    @Test
    void d3_badPutBodyIs400() {
        long talk = acceptedTalk();
        String[] bad = {
                null,
                "not json",
                "[]",
                "\"x\"",
                "{}",
                "{\"talkId\":null}",
                "{\"talkId\":\"1\"}",
                "{\"talkId\":1.5}",
                "{\"talkId\":true}",
                "{\"talkId\":[1]}",
        };
        for (String body : bad) {
            HttpResponse<String> res = putBody("R1", "S1", body);
            assertEquals(400, res.statusCode(), body + ": " + res.body());
        }
        assertEquals(404, get("R1", "S1").statusCode());
    }

    @Test
    void d4_unknownTalkIs404() {
        assertEquals(404, put("R1", "S1", 999999).statusCode());
    }

    @Test
    void d13_unknownRoomBeatsBadBody() {
        assertEquals(404, putBody("R9", "S1", "not json").statusCode());
    }

    // ------------------------------------------------------------ R3, D5: only ACCEPTED

    @Test
    void r3d5_nonAcceptedTalkIs409() {
        long speaker = api.newSpeaker();
        long submitted = api.newTalk(speaker, "PRESENTATION").get("id").asLong();
        assertEquals(409, put("R1", "S1", submitted).statusCode()); // SUBMITTED

        long rejected = api.newTalk(api.newSpeaker(), "PRESENTATION").get("id").asLong();
        Api.body(api.put("/talks/" + rejected + "/status", "{\"status\":\"REJECTED\"}"), 200);
        assertEquals(409, put("R1", "S1", rejected).statusCode());

        long waitlisted = api.newTalk(api.newSpeaker(), "PRESENTATION").get("id").asLong();
        Api.body(api.put("/talks/" + waitlisted + "/status", "{\"status\":\"WAITLISTED\"}"), 200);
        assertEquals(409, put("R1", "S1", waitlisted).statusCode());

        long withdrawnSpeaker = api.newSpeaker();
        long withdrawn = api.newTalk(withdrawnSpeaker, "PRESENTATION").get("id").asLong();
        Api.body(api.post("/talks/" + withdrawn + "/withdraw", "{\"speakerId\":" + withdrawnSpeaker + "}"), 200);
        assertEquals(409, put("R1", "S1", withdrawn).statusCode());
    }

    // ------------------------------------------------------------ R4, D6: workshop fit

    @Test
    void r4d6_workshopOnlyFitsS4() {
        long workshop = acceptedTalk("WORKSHOP");
        assertEquals(409, put("R1", "S1", workshop).statusCode());
        assertEquals(409, put("R1", "S2", workshop).statusCode());
        assertEquals(409, put("R1", "S3", workshop).statusCode());
        assertEquals(409, put("R1", "S5", workshop).statusCode());
        assertEntry(entry(put("R2", "S4", workshop)), "R2", "S4", "13:30", "15:30", workshop);
    }

    @Test
    void r4_nonWorkshopFitsAnySlotIncludingS4() {
        long talk = acceptedTalk("LIGHTNING_10");
        assertEntry(entry(put("R1", "S4", talk)), "R1", "S4", "13:30", "15:30", talk);
    }

    // ------------------------------------------------------------ R5, D7, D8, D9: occupancy and once-only

    @Test
    void r5d7_secondTalkInSameCellIs409() {
        long a = acceptedTalk();
        long b = acceptedTalk();
        entry(put("R1", "S1", a));
        assertEquals(409, put("R1", "S1", b).statusCode());
        assertEntry(entry(get("R1", "S1")), "R1", "S1", "09:00", "10:00", a);
    }

    @Test
    void d9_sameTalkSameCellAgainIs200() {
        long talk = acceptedTalk();
        assertEntry(entry(put("R1", "S1", talk)), "R1", "S1", "09:00", "10:00", talk);
        assertEntry(entry(put("R1", "S1", talk)), "R1", "S1", "09:00", "10:00", talk);
    }

    @Test
    void r5d8_talkAlreadyElsewhereIs409WithoutDelete() {
        long talk = acceptedTalk();
        entry(put("R1", "S1", talk));
        assertEquals(409, put("R2", "S2", talk).statusCode());
        assertEquals(404, get("R2", "S2").statusCode());
        assertEntry(entry(get("R1", "S1")), "R1", "S1", "09:00", "10:00", talk);
    }

    // ------------------------------------------------------------ R6, D10: speaker / co-speaker clash

    @Test
    void r6d10_sameMainSpeakerInSameSlotDifferentRoomsIs409() {
        long speaker = api.newSpeaker();
        long a = acceptedTalkOf(speaker, "PRESENTATION");
        long b = acceptedTalkOf(speaker, "LIGHTNING_20");
        entry(put("R1", "S1", a));
        assertEquals(409, put("R2", "S1", b).statusCode());
        assertEntry(entry(put("R2", "S2", b)), "R2", "S2", "10:15", "11:15", b);
    }

    @Test
    void r6d10_coSpeakerClashInSameSlotIs409() {
        long mainA = api.newSpeaker();
        long shared = api.newSpeaker();
        long talkA = acceptedTalkOf(mainA, "PRESENTATION");
        Api.body(api.put("/talks/" + talkA + "/co-speakers/" + shared, null), 200);
        // shared person is main on another talk
        long talkB = acceptedTalkOf(shared, "LIGHTNING_10");
        entry(put("R1", "S3", talkA));
        assertEquals(409, put("R2", "S3", talkB).statusCode());

        // shared person is co-speaker on both talks in the same slot
        long mainC = api.newSpeaker();
        long talkC = acceptedTalkOf(mainC, "PRESENTATION");
        Api.body(api.put("/talks/" + talkC + "/co-speakers/" + shared, null), 200);
        assertEquals(409, put("R3", "S3", talkC).statusCode());
    }

    @Test
    void r6_differentSpeakersSameSlotDifferentRoomsIsOk() {
        long a = acceptedTalk();
        long b = acceptedTalk();
        entry(put("R1", "S1", a));
        assertEntry(entry(put("R2", "S1", b)), "R2", "S1", "09:00", "10:00", b);
    }

    // ------------------------------------------------------------ R7: later talk changes leave programme

    @Test
    void r7d14_withdrawAfterScheduleLeavesProgramme() {
        long speaker = api.newSpeaker();
        long talk = acceptedTalkOf(speaker, "PRESENTATION");
        entry(put("R1", "S1", talk));
        Api.body(api.post("/talks/" + talk + "/withdraw", "{\"speakerId\":" + speaker + "}"), 200);
        assertEquals("WITHDRAWN", Api.body(api.get("/talks/" + talk), 200).get("status").asText());
        assertEntry(entry(get("R1", "S1")), "R1", "S1", "09:00", "10:00", talk); // R7, D14
    }

    @Test
    void r7d15_coSpeakerAddedAfterScheduleDoesNotRecheckClash() {
        long mainA = api.newSpeaker();
        long mainB = api.newSpeaker();
        long talkA = acceptedTalkOf(mainA, "PRESENTATION");
        long talkB = acceptedTalkOf(mainB, "PRESENTATION");
        entry(put("R1", "S1", talkA));
        entry(put("R2", "S1", talkB));
        // after both are scheduled, make mainA a co-speaker of talkB — programme stays
        Api.body(api.put("/talks/" + talkB + "/co-speakers/" + mainA, null), 200);
        assertEntry(entry(get("R1", "S1")), "R1", "S1", "09:00", "10:00", talkA);
        assertEntry(entry(get("R2", "S1")), "R2", "S1", "09:00", "10:00", talkB);
    }

    // ------------------------------------------------------------ R8: concurrency

    @Test
    void r8_concurrentPutsIntoSameCell_exactlyOneWins() {
        for (int round = 0; round < 8; round++) {
            long a = acceptedTalk();
            long b = acceptedTalk();
            List<CompletableFuture<HttpResponse<String>>> sent = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                sent.add(api.sendAsync("PUT", "/programme/R1/S1", "{\"talkId\":" + a + "}"));
                sent.add(api.sendAsync("PUT", "/programme/R1/S1", "{\"talkId\":" + b + "}"));
            }
            int ok = 0;
            Long winner = null;
            for (CompletableFuture<HttpResponse<String>> f : sent) {
                HttpResponse<String> res = f.join();
                assertTrue(res.statusCode() == 200 || res.statusCode() == 409, res.statusCode() + " " + res.body());
                if (res.statusCode() == 200) {
                    long id = Api.json(res.body()).get("talkId").asLong();
                    if (winner == null) {
                        winner = id;
                    }
                    // D9: same talk again is 200; the other talk must never also win
                    assertEquals(winner, id);
                    ok++;
                }
            }
            assertTrue(ok >= 1);
            assertEquals(winner, entry(get("R1", "S1")).get("talkId").asLong());
            entry(delete("R1", "S1"));
        }
    }
}
