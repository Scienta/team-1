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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Acceptance state machine (specs/acceptance/spec.md): the committee accepts, rejects or waitlists a talk with
 * PUT /talks/{id}/status, the main speaker withdraws it with POST /talks/{id}/withdraw. Comments name the rule (R) or
 * decision (D) each test proves.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AcceptanceTest {

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

    // ------------------------------------------------------------ helpers

    private long talk() {
        return talkOf(api.newSpeaker());
    }

    private long talkOf(long speaker) {
        return api.newTalk(speaker, "PRESENTATION").get("id").asLong();
    }

    private long ratedTalk() {
        long id = talk();
        api.rate(id, 1, 4);
        api.rate(id, 2, 5);
        return id;
    }

    private HttpResponse<String> decide(long talk, String status) {
        return api.put("/talks/" + talk + "/status", "{\"status\":\"" + status + "\"}");
    }

    private HttpResponse<String> withdraw(long talk, long speaker) {
        return api.post("/talks/" + talk + "/withdraw", "{\"speakerId\":" + speaker + "}");
    }

    private String status(long talk) {
        return Api.body(api.get("/talks/" + talk), 200).get("status").asText();
    }

    private void expect(String status, HttpResponse<String> res) {
        assertEquals(status, Api.body(res, 200).get("status").asText());
    }

    private void refused(int code, HttpResponse<String> res, long talk, String unchanged) {
        assertEquals(code, res.statusCode(), res.body());
        assertEquals(unchanged, status(talk)); // R8
    }

    private long waitlisted(long talk) {
        expect("WAITLISTED", decide(talk, "WAITLISTED"));
        return talk;
    }

    private long withdrawn() {
        long speaker = api.newSpeaker();
        long talk = talkOf(speaker);
        expect("WITHDRAWN", withdraw(talk, speaker));
        return talk;
    }

    // ------------------------------------------------------------ R1, R2: decisions from SUBMITTED and WAITLISTED

    @Test
    void r1r2_submittedTalkCanBeAcceptedRejectedOrWaitlisted() {
        long a = ratedTalk();
        JsonNode accepted = Api.body(decide(a, "ACCEPTED"), 200); // R1, R7 (decision response)
        assertEquals("ACCEPTED", accepted.get("status").asText());
        assertEquals(a, accepted.get("id").asLong());
        assertEquals("ACCEPTED", status(a));

        long r = talk();
        expect("REJECTED", decide(r, "REJECTED"));
        assertEquals("REJECTED", status(r));

        long w = talk();
        expect("WAITLISTED", decide(w, "WAITLISTED"));
        assertEquals("WAITLISTED", status(w));
    }

    @Test
    void r2_waitlistedTalkCanStillBeAcceptedOrRejected() {
        long a = waitlisted(ratedTalk());
        expect("ACCEPTED", decide(a, "ACCEPTED"));

        long r = waitlisted(talk());
        expect("REJECTED", decide(r, "REJECTED"));
    }

    @Test
    void d6_waitlistingAWaitlistedTalkIs200AndUnchanged() {
        long w = waitlisted(talk());
        expect("WAITLISTED", decide(w, "WAITLISTED"));
        assertEquals("WAITLISTED", status(w));
    }

    @Test
    void r2d4_acceptedIsFinal() {
        long a = ratedTalk();
        expect("ACCEPTED", decide(a, "ACCEPTED"));
        refused(409, decide(a, "REJECTED"), a, "ACCEPTED");
        refused(409, decide(a, "WAITLISTED"), a, "ACCEPTED");
    }

    @Test
    void r2d4_rejectedIsFinal() {
        long r = ratedTalk();
        expect("REJECTED", decide(r, "REJECTED"));
        refused(409, decide(r, "ACCEPTED"), r, "REJECTED");
        refused(409, decide(r, "WAITLISTED"), r, "REJECTED");
    }

    @Test
    // D7: harness acceptance/check-3 and check-5 failed on a 200 for this (tick 141-142)
    void d7_repeatingAFinalDecisionIs409AndUnchanged() {
        long a = ratedTalk();
        expect("ACCEPTED", decide(a, "ACCEPTED"));
        refused(409, decide(a, "ACCEPTED"), a, "ACCEPTED");

        long r = talk();
        expect("REJECTED", decide(r, "REJECTED"));
        refused(409, decide(r, "REJECTED"), r, "REJECTED");
    }

    // ------------------------------------------------------------ R3, D5: two different reviewers to accept

    @Test
    void r3d5_acceptNeedsTwoDifferentReviewers() {
        long none = talk();
        refused(409, decide(none, "ACCEPTED"), none, "SUBMITTED");

        long one = talk();
        api.rate(one, 7, 5);
        refused(409, decide(one, "ACCEPTED"), one, "SUBMITTED");

        api.rate(one, 7, 3); // the same reviewer again still counts once
        refused(409, decide(one, "ACCEPTED"), one, "SUBMITTED");

        api.rate(one, 8, 4);
        expect("ACCEPTED", decide(one, "ACCEPTED"));
    }

    @Test
    void r3d5_waitlistedTalkAlsoNeedsTwoReviewersToBeAccepted() {
        long w = waitlisted(talk());
        api.rate(w, 1, 5);
        refused(409, decide(w, "ACCEPTED"), w, "WAITLISTED");
    }

    @Test
    void r3_rejectingAndWaitlistingNeedNoRatings() {
        expect("REJECTED", decide(talk(), "REJECTED"));
        expect("WAITLISTED", decide(talk(), "WAITLISTED"));
    }

    // ------------------------------------------------------------ R4, R5, R6: withdrawal

    @Test
    void r4_mainSpeakerCanWithdrawFromSubmittedWaitlistedOrAccepted() {
        long s1 = api.newSpeaker();
        long submitted = talkOf(s1);
        JsonNode res = Api.body(withdraw(submitted, s1), 200); // R7 (withdraw response)
        assertEquals("WITHDRAWN", res.get("status").asText());
        assertEquals(submitted, res.get("id").asLong());
        assertEquals("WITHDRAWN", status(submitted));

        long s2 = api.newSpeaker();
        long w = waitlisted(talkOf(s2));
        expect("WITHDRAWN", withdraw(w, s2));

        long s3 = api.newSpeaker();
        long a = talkOf(s3);
        api.rate(a, 1, 4);
        api.rate(a, 2, 4);
        expect("ACCEPTED", decide(a, "ACCEPTED"));
        expect("WITHDRAWN", withdraw(a, s3));
        assertEquals("WITHDRAWN", status(a));
    }

    @Test
    void r4d11_cannotWithdrawARejectedTalk() {
        long speaker = api.newSpeaker();
        long r = talkOf(speaker);
        expect("REJECTED", decide(r, "REJECTED"));
        refused(409, withdraw(r, speaker), r, "REJECTED");
    }

    @Test
    void r6d11_withdrawingTwiceIs409() {
        long speaker = api.newSpeaker();
        long t = talkOf(speaker);
        expect("WITHDRAWN", withdraw(t, speaker));
        refused(409, withdraw(t, speaker), t, "WITHDRAWN");
    }

    @Test
    void r6d4_noDecisionOnAWithdrawnTalk() {
        long t = withdrawn();
        api.rate(t, 1, 5);
        api.rate(t, 2, 5);
        refused(409, decide(t, "ACCEPTED"), t, "WITHDRAWN");
        refused(409, decide(t, "REJECTED"), t, "WITHDRAWN");
        refused(409, decide(t, "WAITLISTED"), t, "WITHDRAWN");
    }

    @Test
    void r5d9_anotherSpeakerCannotWithdraw() {
        long main = api.newSpeaker();
        long t = talkOf(main);
        refused(403, withdraw(t, api.newSpeaker()), t, "SUBMITTED");
    }

    @Test
    void r5d9_aCoSpeakerCannotWithdraw() {
        long main = api.newSpeaker();
        long co = api.newSpeaker();
        long t = talkOf(main);
        Api.body(api.put("/talks/" + t + "/co-speakers/" + co, null), 200);
        refused(403, withdraw(t, co), t, "SUBMITTED");
    }

    @Test
    void r5d10_anUnregisteredSpeakerCannotWithdraw() {
        long t = talk();
        refused(403, withdraw(t, 999999), t, "SUBMITTED");
    }

    @Test
    void d11d12_aStrangerGets403WhateverTheState() {
        long r = talk();
        expect("REJECTED", decide(r, "REJECTED"));
        refused(403, withdraw(r, api.newSpeaker()), r, "REJECTED");

        long w = withdrawn();
        refused(403, withdraw(w, api.newSpeaker()), w, "WITHDRAWN");
        refused(403, withdraw(w, 999999), w, "WITHDRAWN");
    }

    // ------------------------------------------------------------ D1, D2, D3, D8, D12: invalid requests

    @Test
    void d1_unknownTalkIs404() {
        assertEquals(404, decide(999999, "REJECTED").statusCode());
        assertEquals(404, withdraw(999999, api.newSpeaker()).statusCode());
    }

    @Test
    void d12_unknownTalkIs404EvenWithABadBody() {
        assertEquals(404, api.put("/talks/999999/status", "{\"status\":\"nope\"}").statusCode());
        assertEquals(404, api.put("/talks/999999/status", "not json").statusCode());
        assertEquals(404, api.put("/talks/999999/status", null).statusCode());
        assertEquals(404, api.post("/talks/999999/withdraw", "{}").statusCode());
        assertEquals(404, api.post("/talks/999999/withdraw", "not json").statusCode());
    }

    @Test
    void d2_statusMustBeOneOfTheThreeDecisions() {
        long t = ratedTalk();
        String[] bad = {
                "{}",
                "{\"status\":null}",
                "{\"status\":1}",
                "{\"status\":true}",
                "{\"status\":[\"ACCEPTED\"]}",
                "{\"status\":\"MAYBE\"}",
                "{\"status\":\"accepted\"}",
                "{\"status\":\"Accepted\"}",
                "{\"status\":\" ACCEPTED\"}",
                "{\"status\":\"SUBMITTED\"}",
                "{\"status\":\"WITHDRAWN\"}",
        };
        for (String body : bad) {
            HttpResponse<String> res = api.put("/talks/" + t + "/status", body);
            assertEquals(400, res.statusCode(), body + ": " + res.body());
        }
        assertEquals("SUBMITTED", status(t)); // R8
    }

    @Test
    void d2_aBadStatusOnAFinalTalkIs400Not409() {
        long t = talk();
        expect("REJECTED", decide(t, "REJECTED"));
        refused(400, decide(t, "SUBMITTED"), t, "REJECTED");
    }

    @Test
    void d3_missingOrUnreadableBodyIs400() {
        long t = talk();
        refused(400, api.put("/talks/" + t + "/status", null), t, "SUBMITTED");
        refused(400, api.put("/talks/" + t + "/status", "not json"), t, "SUBMITTED");
        refused(400, api.put("/talks/" + t + "/status", "[\"ACCEPTED\"]"), t, "SUBMITTED");
        refused(400, api.put("/talks/" + t + "/status", "\"ACCEPTED\""), t, "SUBMITTED");
        refused(400, api.post("/talks/" + t + "/withdraw", null), t, "SUBMITTED");
        refused(400, api.post("/talks/" + t + "/withdraw", "not json"), t, "SUBMITTED");
    }

    @Test
    void d8_speakerIdMustBeAnInteger() {
        long speaker = api.newSpeaker();
        long t = talkOf(speaker);
        String[] bad = {
                "{}",
                "{\"speakerId\":null}",
                "{\"speakerId\":\"" + speaker + "\"}",
                "{\"speakerId\":" + speaker + ".5}",
                "{\"speakerId\":true}",
                "{\"speakerId\":[" + speaker + "]}",
        };
        for (String body : bad) {
            HttpResponse<String> res = api.post("/talks/" + t + "/withdraw", body);
            assertEquals(400, res.statusCode(), body + ": " + res.body());
        }
        assertEquals("SUBMITTED", status(t)); // R8
    }

    @Test
    void d12_badBodyBeats403And409() {
        long t = withdrawn();
        refused(400, api.post("/talks/" + t + "/withdraw", "{}"), t, "WITHDRAWN");
        refused(400, decide(t, "nope"), t, "WITHDRAWN");
    }

    // ------------------------------------------------------------ R7: status shows everywhere

    @Test
    void r7_statusShowsInEveryTalkResponse() {
        long main = api.newSpeaker();
        long co = api.newSpeaker();
        long t = talkOf(main);
        expect("WAITLISTED", decide(t, "WAITLISTED"));

        assertEquals("WAITLISTED", status(t));
        assertEquals("WAITLISTED", find(Api.body(api.get("/talks"), 200), t).get("status").asText());
        assertEquals("WAITLISTED", find(Api.body(api.get("/talks?speakerId=" + main), 200), t).get("status").asText());
        assertEquals("WAITLISTED", find(Api.body(api.get("/talks?sort=rating"), 200), t).get("status").asText());
        assertEquals("WAITLISTED", find(Api.body(api.get("/speakers/" + main + "/talks"), 200), t).get("status").asText());
        expect("WAITLISTED", api.put("/talks/" + t + "/co-speakers/" + co, null));
        expect("WAITLISTED", api.send("DELETE", "/talks/" + t + "/co-speakers/" + co, null));

        expect("WITHDRAWN", withdraw(t, main));
        assertEquals("WITHDRAWN", find(Api.body(api.get("/speakers/" + main + "/talks"), 200), t).get("status").asText());
    }

    // D14
    @Test
    void d14_adminStatsCountTheNewStatuses() {
        expect("REJECTED", decide(talk(), "REJECTED"));
        withdrawn();
        JsonNode byStatus = Api.body(api.get("/admin/stats"), 200).get("byStatus");
        assertTrue(byStatus.path("REJECTED").asInt() >= 1, byStatus.toString());
        assertTrue(byStatus.path("WITHDRAWN").asInt() >= 1, byStatus.toString());
    }

    // D13
    @Test
    void d13_withdrawnAndRejectedTalksStillCountTowardsTheLimit() {
        long speaker = api.newSpeaker();
        long a = talkOf(speaker);
        long b = talkOf(speaker);
        talkOf(speaker);
        expect("WITHDRAWN", withdraw(a, speaker));
        expect("REJECTED", decide(b, "REJECTED"));
        HttpResponse<String> fourth = api.post("/talks", "{\"title\":\"One more\",\"format\":\"PRESENTATION\","
                + "\"level\":\"BEGINNER\",\"speakerId\":" + speaker + "}");
        assertEquals(409, fourth.statusCode(), fourth.body());
    }

    @Test
    void ratingsKeepWorkingInAnyStatus() {
        long t = withdrawn();
        api.rate(t, 3, 2);
        assertEquals(1, Api.body(api.get("/talks/" + t), 200).get("ratingCount").asInt());
    }

    // ------------------------------------------------------------ R9: concurrency

    @Test
    void r9_concurrentRejectAndAcceptOnAWaitlistedTalk_exactlyOneWins() {
        for (int round = 0; round < 10; round++) {
            long t = waitlisted(ratedTalk());
            List<CompletableFuture<HttpResponse<String>>> sent = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                sent.add(api.sendAsync("PUT", "/talks/" + t + "/status", "{\"status\":\"REJECTED\"}"));
                sent.add(api.sendAsync("PUT", "/talks/" + t + "/status", "{\"status\":\"ACCEPTED\"}"));
            }
            String winner = null;
            int ok = 0;
            for (CompletableFuture<HttpResponse<String>> f : sent) {
                HttpResponse<String> res = f.join();
                assertTrue(res.statusCode() == 200 || res.statusCode() == 409, res.statusCode() + " " + res.body());
                if (res.statusCode() == 200) {
                    String s = Api.json(res.body()).get("status").asText();
                    if (winner == null) {
                        winner = s;
                    }
                    ok++;
                }
            }
            // a decision is final, also the same one again (D7): exactly one request wins
            assertEquals(1, ok, "more than one decision succeeded");
            assertEquals(winner, status(t));
        }
    }

    @Test
    void r9_concurrentWithdrawAndRejectNeverBothSucceed() {
        for (int round = 0; round < 10; round++) {
            long speaker = api.newSpeaker();
            long t = talkOf(speaker);
            List<CompletableFuture<HttpResponse<String>>> sent = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                sent.add(api.sendAsync("PUT", "/talks/" + t + "/status", "{\"status\":\"REJECTED\"}"));
                sent.add(api.sendAsync("POST", "/talks/" + t + "/withdraw", "{\"speakerId\":" + speaker + "}"));
            }
            int withdrawals = 0;
            int rejections = 0;
            for (CompletableFuture<HttpResponse<String>> f : sent) {
                HttpResponse<String> res = f.join();
                assertTrue(res.statusCode() == 200 || res.statusCode() == 409, res.statusCode() + " " + res.body());
                if (res.statusCode() == 200) {
                    if ("WITHDRAWN".equals(Api.json(res.body()).get("status").asText())) {
                        withdrawals++;
                    } else {
                        rejections++;
                    }
                }
            }
            // REJECTED → withdraw is refused, WITHDRAWN → anything is refused: only one kind can win,
            // and a talk is withdrawn at most once.
            assertTrue(withdrawals == 0 || rejections == 0, withdrawals + " withdrawals and " + rejections + " rejections");
            assertTrue(withdrawals <= 1, withdrawals + " withdrawals");
            String end = status(t);
            assertEquals(withdrawals == 1 ? "WITHDRAWN" : "REJECTED", end);
        }
    }

    private static JsonNode find(JsonNode list, long id) {
        for (JsonNode t : list) {
            if (t.get("id").asLong() == id) {
                return t;
            }
        }
        throw new AssertionError("talk " + id + " not in " + list);
    }
}
