package no.scienta.cfp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import tools.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Programme export (specs/programme-export/spec.md). Comments name the rule (R) or decision (D) each test proves.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProgrammeExportTest {

    private static final String HEADER = "slot,start,end,room,talkId,title,format,speakers\n";

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
        jdbc.update("DELETE FROM programme");
    }

    // ------------------------------------------------------------ helpers

    private long speaker(String name) {
        String email = "export-" + UUID.randomUUID() + "@example.com";
        return Api.body(api.post("/speakers", "{\"name\":" + quote(name) + ",\"email\":\"" + email + "\"}"), 201)
                .get("id").asLong();
    }

    private long acceptedTalk(long speaker, String title, String format) {
        long id = Api.body(api.post("/talks", "{\"title\":" + quote(title) + ",\"format\":\"" + format
                + "\",\"level\":\"BEGINNER\",\"speakerId\":" + speaker + "}"), 201).get("id").asLong();
        api.rate(id, 1, 4);
        api.rate(id, 2, 5);
        Api.body(api.put("/talks/" + id + "/status", "{\"status\":\"ACCEPTED\"}"), 200);
        return id;
    }

    private long acceptedTalk(String speakerName, String title) {
        return acceptedTalk(speaker(speakerName), title, "PRESENTATION");
    }

    private void place(String room, String slot, long talk) {
        Api.body(api.put("/programme/" + room + "/" + slot, "{\"talkId\":" + talk + "}"), 200);
    }

    private void addCoSpeaker(long talk, long speaker) {
        Api.body(api.put("/talks/" + talk + "/co-speakers/" + speaker, null), 200);
    }

    private JsonNode json() {
        return Api.body(api.get("/programme"), 200);
    }

    private String csv() {
        HttpResponse<String> res = api.get("/programme.csv");
        assertEquals(200, res.statusCode(), res.body());
        return res.body();
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                default: sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    private static List<String> speakers(JsonNode row) {
        List<String> names = new ArrayList<>();
        row.get("speakers").forEach(n -> names.add(n.asText()));
        return names;
    }

    // ------------------------------------------------------------ R11: empty programme

    @Test
    void r11_emptyProgrammeIsEmptyArrayAndHeaderOnly() {
        JsonNode rows = json();
        assertTrue(rows.isArray());
        assertEquals(0, rows.size()); // R11
        assertEquals(HEADER, csv()); // R11, R8, R9
    }

    // ------------------------------------------------------------ R1, R2, R3, R4, R8, R9, D18, D19

    @Test
    void r1r2r3r4_oneRowPerScheduledTalkInSlotThenRoomOrder() {
        long s5r1 = acceptedTalk("Speaker A", "Talk S5 R1");
        long s1r3 = acceptedTalk("Speaker B", "Talk S1 R3");
        long s1r1 = acceptedTalk("Speaker C", "Talk S1 R1");
        long s4r2 = acceptedTalk(speaker("Speaker D"), "Workshop S4 R2", "WORKSHOP");
        long s2r2 = acceptedTalk("Speaker E", "Talk S2 R2");
        // placed out of order on purpose
        place("R1", "S5", s5r1);
        place("R3", "S1", s1r3);
        place("R2", "S4", s4r2);
        place("R1", "S1", s1r1);
        place("R2", "S2", s2r2);

        JsonNode rows = json();
        assertEquals(5, rows.size()); // R2
        String[][] expected = {
                {"S1", "09:00", "10:00", "R1", String.valueOf(s1r1), "Talk S1 R1", "PRESENTATION", "Speaker C"},
                {"S1", "09:00", "10:00", "R3", String.valueOf(s1r3), "Talk S1 R3", "PRESENTATION", "Speaker B"},
                {"S2", "10:15", "11:15", "R2", String.valueOf(s2r2), "Talk S2 R2", "PRESENTATION", "Speaker E"},
                {"S4", "13:30", "15:30", "R2", String.valueOf(s4r2), "Workshop S4 R2", "WORKSHOP", "Speaker D"},
                {"S5", "15:45", "16:45", "R1", String.valueOf(s5r1), "Talk S5 R1", "PRESENTATION", "Speaker A"},
        };
        StringBuilder expectedCsv = new StringBuilder(HEADER);
        for (int i = 0; i < expected.length; i++) {
            JsonNode row = rows.get(i);
            String[] e = expected[i];
            // R4: fields in this order
            List<String> fields = new ArrayList<>();
            row.propertyNames().forEach(fields::add);
            assertEquals(List.of("slot", "start", "end", "room", "talkId", "title", "format", "speakers"), fields);
            assertEquals(e[0], row.get("slot").asText()); // R3
            assertEquals(e[1], row.get("start").asText()); // R4
            assertEquals(e[2], row.get("end").asText());
            assertEquals(e[3], row.get("room").asText()); // R3
            assertTrue(row.get("talkId").isIntegralNumber()); // D18
            assertEquals(Long.parseLong(e[4]), row.get("talkId").asLong());
            assertEquals(e[5], row.get("title").asText());
            assertEquals(e[6], row.get("format").asText());
            assertTrue(row.get("speakers").isArray()); // D18
            assertEquals(List.of(e[7]), speakers(row));
            expectedCsv.append(String.join(",", e)).append('\n');
        }
        assertEquals(expectedCsv.toString(), csv()); // R1, R2, R3 same order, R8, R9
    }

    @Test
    void r9_csvHasUnixLineEndingsAndNoBlankLineAtEnd() {
        place("R1", "S1", acceptedTalk("Ada", "One"));
        place("R2", "S1", acceptedTalk("Bea", "Two"));
        String body = csv();
        assertFalse(body.contains("\r")); // R9
        assertTrue(body.endsWith("\n")); // R9
        assertFalse(body.endsWith("\n\n")); // R9
        assertEquals(3, body.split("\n", -1).length - 1); // header + 2 rows, R2
        assertTrue(body.startsWith(HEADER)); // R8, D13 no BOM
    }

    // ------------------------------------------------------------ R5, R6, D3, D4

    @Test
    void r6_mainSpeakerFirstThenCoSpeakersInAddOrder() {
        long grace = speaker("Grace Hopper");
        long talk = acceptedTalk(grace, "Compilers", "PRESENTATION");
        long linus = speaker("Linus");
        long ada = speaker("Ada Lovelace");
        addCoSpeaker(talk, ada);
        addCoSpeaker(talk, linus);
        place("R1", "S2", talk);

        assertEquals(List.of("Grace Hopper", "Ada Lovelace", "Linus"), speakers(json().get(0))); // R6
        assertEquals(HEADER + "S2,10:15,11:15,R1," + talk + ",Compilers,PRESENTATION,Grace Hopper; Ada Lovelace; Linus\n",
                csv()); // R6
    }

    @Test
    void r5d3d4_rowShowsCurrentSpeakersNotThoseAtSchedulingTime() {
        long main = speaker("Old Name");
        long talk = acceptedTalk(main, "Live data", "PRESENTATION");
        long first = speaker("First Co");
        addCoSpeaker(talk, first);
        place("R3", "S3", talk);

        // D4: rename after scheduling
        Api.body(api.put("/speakers/" + main, "{\"name\":\"New Name\",\"email\":\"renamed-" + UUID.randomUUID()
                + "@example.com\"}"), 200);
        // D3: add one, remove the earlier one
        long second = speaker("Second Co");
        addCoSpeaker(talk, second);
        Api.body(api.send("DELETE", "/talks/" + talk + "/co-speakers/" + first, null), 200);

        assertEquals(List.of("New Name", "Second Co"), speakers(json().get(0))); // R5
        assertTrue(csv().endsWith(",Live data,PRESENTATION,New Name; Second Co\n")); // R5
    }

    @Test
    void a4_removedAndReAddedCoSpeakerGoesToTheEnd() {
        long talk = acceptedTalk("Main", "Order");
        long a = speaker("A");
        long b = speaker("B");
        addCoSpeaker(talk, a);
        addCoSpeaker(talk, b);
        Api.body(api.send("DELETE", "/talks/" + talk + "/co-speakers/" + a, null), 200);
        addCoSpeaker(talk, a);
        place("R1", "S1", talk);
        assertEquals(List.of("Main", "B", "A"), speakers(json().get(0))); // A4, R6
    }

    // ------------------------------------------------------------ R10, D5, D7, D8, D9

    @Test
    void r10_fieldWithCommaAndQuotesIsQuotedWithDoubledQuotes() {
        long grace = speaker("Grace Hopper");
        long talk = acceptedTalk(grace, "Kotlin, \"really\" fast", "WORKSHOP");
        addCoSpeaker(talk, speaker("Ada Lovelace"));
        place("R1", "S4", talk);

        assertEquals("Kotlin, \"really\" fast", json().get(0).get("title").asText()); // D5
        assertEquals(HEADER + "S4,13:30,15:30,R1," + talk
                + ",\"Kotlin, \"\"really\"\" fast\",WORKSHOP,Grace Hopper; Ada Lovelace\n", csv()); // R10, spec example
    }

    @Test
    void r10_quoteOnlyFieldIsQuoted() {
        long talk = acceptedTalk("Q", "Say \"hi\"");
        place("R2", "S3", talk);
        assertTrue(csv().contains("," + talk + ",\"Say \"\"hi\"\"\",PRESENTATION,Q\n")); // R10
    }

    @Test
    void r10_speakersJoinedThenQuotedWhenANameHasAComma() {
        long talk = acceptedTalk("Hopper, Grace", "Names");
        addCoSpeaker(talk, speaker("Ada"));
        place("R3", "S5", talk);
        assertEquals(List.of("Hopper, Grace", "Ada"), speakers(json().get(0)));
        assertTrue(csv().endsWith(",Names,PRESENTATION,\"Hopper, Grace; Ada\"\n")); // R10 applied to the joined string
    }

    @Test
    void d7d8_semicolonAndSurroundingSpacesAreWrittenAsIs() {
        long talk = acceptedTalk("  Spaced; Name ", "  padded title ");
        place("R1", "S3", talk);
        assertEquals("  padded title ", json().get(0).get("title").asText()); // D5
        assertEquals(List.of("  Spaced; Name "), speakers(json().get(0))); // D5
        assertTrue(csv().endsWith("," + talk + ",  padded title ,PRESENTATION,  Spaced; Name \n")); // D7, D8
    }

    @Test
    void r10d9_lineBreaksInsideFieldsAreQuotedAndKeptByteForByte() {
        long lf = acceptedTalk("LF", "Line one\nLine two");
        long crlf = acceptedTalk("CRLF", "Win one\r\nWin two");
        long cr = acceptedTalk("CR", "Mac one\rMac two");
        place("R1", "S1", lf);
        place("R2", "S1", crlf);
        place("R3", "S1", cr);
        assertEquals(HEADER
                + "S1,09:00,10:00,R1," + lf + ",\"Line one\nLine two\",PRESENTATION,LF\n"
                + "S1,09:00,10:00,R2," + crlf + ",\"Win one\r\nWin two\",PRESENTATION,CRLF\n"
                + "S1,09:00,10:00,R3," + cr + ",\"Mac one\rMac two\",PRESENTATION,CR\n", csv()); // R10, D9
    }

    @Test
    void r4_utf8NamesSurviveInCsvAndJson() {
        long talk = acceptedTalk("Bjørn Ærø", "Smørbrød og kaffe ☕");
        place("R2", "S5", talk);
        assertEquals("Smørbrød og kaffe ☕", json().get(0).get("title").asText());
        assertTrue(csv().endsWith(",Smørbrød og kaffe ☕,PRESENTATION,Bjørn Ærø\n")); // R7 UTF-8
    }

    // ------------------------------------------------------------ D2, D6, D20

    @Test
    void d2_withdrawnTalkStaysInTheExport() {
        long main = speaker("Withdrawer");
        long talk = acceptedTalk(main, "Gone soon", "PRESENTATION");
        place("R2", "S2", talk);
        Api.body(api.post("/talks/" + talk + "/withdraw", "{\"speakerId\":" + main + "}"), 200);
        JsonNode rows = json();
        assertEquals(1, rows.size()); // D2
        assertEquals("Gone soon", rows.get(0).get("title").asText());
        assertFalse(rows.get(0).has("status")); // D2: no status column
        assertEquals(HEADER + "S2,10:15,11:15,R2," + talk + ",Gone soon,PRESENTATION,Withdrawer\n", csv());
    }

    @Test
    void d6_twoSpeakersWithTheSameNameAreBothListed() {
        long talk = acceptedTalk("Kim", "Twins");
        addCoSpeaker(talk, speaker("Kim"));
        place("R1", "S5", talk);
        assertEquals(List.of("Kim", "Kim"), speakers(json().get(0))); // D6
        assertTrue(csv().endsWith(",Twins,PRESENTATION,Kim; Kim\n"));
    }

    @Test
    void d20_missingCoSpeakerRowIsSkippedNot500() {
        long talk = acceptedTalk("Present", "Ghost co");
        place("R1", "S2", talk);
        jdbc.update("INSERT INTO co_speaker (talk_id, speaker_id) VALUES (?, ?)", talk, 999_999_999);
        assertEquals(List.of("Present"), speakers(json().get(0))); // D20
        assertTrue(csv().endsWith(",Ghost co,PRESENTATION,Present\n")); // D20
    }

    // ------------------------------------------------------------ R7, D10, D11, D12, D13

    @Test
    void r7d10d11_csvIsTextCsvUtf8WhateverTheAcceptHeader() {
        place("R1", "S1", acceptedTalk("Acc", "Accept"));
        String expected = csv();
        for (String accept : new String[]{"application/json", "text/html", "*/*", "text/csv", "application/xml"}) {
            HttpResponse<String> res = api.get("/programme.csv", accept);
            assertEquals(200, res.statusCode(), accept + ": " + res.body()); // D11
            String type = res.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
            assertTrue(type.startsWith("text/csv"), accept + " gave " + type); // R7
            assertTrue(type.replace(" ", "").contains("charset=utf-8"), type); // D10
            assertEquals(expected, res.body());
        }
        HttpResponse<String> plain = api.get("/programme.csv");
        assertTrue(plain.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT).startsWith("text/csv"));
        assertFalse(plain.body().startsWith("﻿")); // D13
    }

    @Test
    void d12_jsonViewIsJsonWhateverTheAcceptHeader() {
        place("R1", "S1", acceptedTalk("Js", "Json"));
        for (String accept : new String[]{"text/csv", "text/html", "*/*", "application/json", "application/xml"}) {
            HttpResponse<String> res = api.get("/programme", accept);
            assertEquals(200, res.statusCode(), accept + ": " + res.body()); // D12
            assertTrue(res.headers().firstValue("Content-Type").orElse("").startsWith("application/json"), accept);
            assertEquals(1, Api.json(res.body()).size());
        }
    }

    // ------------------------------------------------------------ D1, D14, D15, D16, D17

    @Test
    void d1_exportPathsDoNotShadowCellPaths() {
        long talk = acceptedTalk("Cell", "Cell talk");
        place("R1", "S1", talk);
        assertEquals(talk, Api.body(api.get("/programme/R1/S1"), 200).get("talkId").asLong()); // D1
        assertEquals(404, api.get("/programme/R2/S1").statusCode()); // D1: scheduling unchanged
        assertEquals(1, json().size());
        assertTrue(csv().startsWith(HEADER));
    }

    @Test
    void d14_queryParametersAreIgnored() {
        place("R1", "S1", acceptedTalk("Qp", "Query"));
        String csv = csv();
        String json = api.get("/programme").body();
        assertEquals(csv, api.get("/programme.csv?room=R2&format=json").body()); // D14
        assertEquals(json, api.get("/programme?slot=S5&sort=desc").body()); // D14
    }

    @Test
    void d15_trailingSlashOnProgrammeIsTheSameAsWithout() {
        place("R1", "S1", acceptedTalk("Ts", "Slash"));
        HttpResponse<String> res = api.get("/programme/");
        assertEquals(200, res.statusCode(), res.body()); // D15
        assertEquals(api.get("/programme").body(), res.body());
    }

    @Test
    void d16_writeMethodsOnExportPathsAre405() {
        for (String method : new String[]{"POST", "PUT", "DELETE"}) {
            assertEquals(405, api.send(method, "/programme", "{}").statusCode(), method + " /programme"); // D16
            assertEquals(405, api.send(method, "/programme.csv", "{}").statusCode(), method + " /programme.csv");
        }
    }

    @Test
    void d17_headReturnsHeadersWithoutBody() {
        HttpResponse<String> csv = api.send("HEAD", "/programme.csv", null);
        assertEquals(200, csv.statusCode()); // D17
        assertTrue(csv.headers().firstValue("Content-Type").orElse("").startsWith("text/csv"));
        assertEquals("", csv.body());
        HttpResponse<String> json = api.send("HEAD", "/programme", null);
        assertEquals(200, json.statusCode()); // D17
        assertEquals("", json.body());
    }

    // ------------------------------------------------------------ Concurrency

    @Test
    void concurrency_exportsDuringSchedulingEditsAreAlways200AndConsistent() {
        long[] talks = new long[6];
        for (int i = 0; i < talks.length; i++) {
            talks[i] = acceptedTalk("Par " + i, "Parallel " + i);
        }
        String[] rooms = {"R1", "R2", "R3"};
        List<CompletableFuture<HttpResponse<String>>> exports = new ArrayList<>();
        List<CompletableFuture<HttpResponse<String>>> edits = new ArrayList<>();
        for (int round = 0; round < 5; round++) {
            for (int i = 0; i < talks.length; i++) {
                String path = "/programme/" + rooms[i % 3] + "/" + (i < 3 ? "S1" : "S2");
                edits.add(api.sendAsync(round % 2 == 0 ? "PUT" : "DELETE", path, "{\"talkId\":" + talks[i] + "}"));
            }
            for (int i = 0; i < 4; i++) {
                exports.add(api.sendAsync("GET", "/programme", null));
                exports.add(api.sendAsync("GET", "/programme.csv", null));
            }
        }
        for (CompletableFuture<HttpResponse<String>> f : edits) {
            int status = f.join().statusCode();
            assertTrue(status == 200 || status == 404 || status == 409, "edit gave " + status);
        }
        for (CompletableFuture<HttpResponse<String>> f : exports) {
            HttpResponse<String> res = f.join();
            assertEquals(200, res.statusCode(), res.body()); // never 500 / serialization failure
            if (res.uri().getPath().endsWith(".csv")) {
                assertTrue(res.body().startsWith(HEADER));
                assertTrue(res.body().endsWith("\n"));
                assertFalse(res.body().endsWith("\n\n") && !res.body().equals(HEADER));
            } else {
                assertTrue(Api.json(res.body()).isArray());
            }
        }
        // after the dust settles, both views agree with the cells
        int occupied = jdbc.queryForObject("SELECT COUNT(*) FROM programme", Integer.class);
        assertEquals(occupied, json().size());
        assertEquals(occupied + 1, csv().split("\n", -1).length - 1);
    }
}
