package no.scienta.cfp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import tools.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Workshop registration with capacity (specs/workshop-registration/spec.md): attendees register for an accepted
 * workshop with POST /workshops/{id}/registrations, at most 20 per workshop and one per email; the organisers list
 * them with GET. Comments name the rule (R) or decision (D) each test proves.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WorkshopRegistrationTest {

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
    }

    // ------------------------------------------------------------ helpers

    private long talk(long speaker, String format) {
        return api.newTalk(speaker, format).get("id").asLong();
    }

    private long accepted(long speaker, String format) {
        long id = talk(speaker, format);
        api.rate(id, 1, 4);
        api.rate(id, 2, 5);
        assertEquals("ACCEPTED", Api.body(api.put("/talks/" + id + "/status", "{\"status\":\"ACCEPTED\"}"), 200)
                .get("status").asString());
        return id;
    }

    /** An accepted workshop, with a speaker of its own. */
    private long workshop() {
        return accepted(api.newSpeaker(), "WORKSHOP");
    }

    private static String uniqueEmail() {
        return "attendee-" + UUID.randomUUID() + "@example.com";
    }

    private static String body(String name, String email) {
        return "{\"name\":\"" + name + "\",\"email\":\"" + email + "\"}";
    }

    private HttpResponse<String> register(long workshop, String json) {
        return api.post("/workshops/" + workshop + "/registrations", json);
    }

    private JsonNode registered(long workshop, String name, String email) {
        return Api.body(register(workshop, body(name, email)), 201);
    }

    private JsonNode list(long workshop) {
        JsonNode list = Api.body(api.get("/workshops/" + workshop + "/registrations"), 200);
        assertTrue(list.isArray(), list.toString());
        return list;
    }

    private void fill(long workshop, int n) {
        for (int i = 0; i < n; i++) {
            registered(workshop, "Attendee " + i, uniqueEmail());
        }
    }

    private void refused(int code, HttpResponse<String> res, long workshop, int unchangedCount) {
        assertEquals(code, res.statusCode(), res.body());
        assertEquals(unchangedCount, list(workshop).size()); // nothing stored
    }

    // ------------------------------------------------------------ R1: register

    @Test
    void r1_registeringReturns201WithTheRegistration() {
        long w = workshop();
        JsonNode r = registered(w, "Ada Lovelace", "ada@example.com");
        assertTrue(r.get("id").isIntegralNumber(), r.toString());
        assertEquals(w, r.get("workshopId").asLong());
        assertEquals("Ada Lovelace", r.get("name").asString());
        assertEquals("ada@example.com", r.get("email").asString());
        assertEquals(4, r.size(), r.toString());

        JsonNode list = list(w);
        assertEquals(1, list.size());
        assertEquals(r, list.get(0));
    }

    @Test
    void r1_registrationIdsAreUniqueAcrossWorkshops() {
        long a = registered(workshop(), "Ada", uniqueEmail()).get("id").asLong();
        long b = registered(workshop(), "Ada", uniqueEmail()).get("id").asLong();
        assertTrue(a != b);
    }

    // ------------------------------------------------------------ R2, D2: only workshops

    // The harness's check-2 failed on 404 for both: registering for an accepted presentation (tick 162), then listing
    // it (tick 174). Registering is 409; listing is 200 with an empty list.
    @Test
    void r2d2_registeringForATalkThatIsNotAWorkshopIs409AndItsListIsEmpty() {
        long speaker = api.newSpeaker();
        long presentation = accepted(speaker, "PRESENTATION");
        long lightning = accepted(speaker, "LIGHTNING_10");
        long lightning20 = talk(speaker, "LIGHTNING_20");
        for (long t : new long[] {presentation, lightning, lightning20}) {
            HttpResponse<String> res = register(t, body("Ada", uniqueEmail()));
            assertEquals(409, res.statusCode(), res.body());
            assertEquals(0, list(t).size());
        }
    }

    // ------------------------------------------------------------ R3, D8: only accepted workshops

    @Test
    void r3d8_aWorkshopThatIsNotAcceptedIs409AndStoresNothing() {
        long speaker = api.newSpeaker();
        long submitted = talk(speaker, "WORKSHOP");
        refused(409, register(submitted, body("Ada", uniqueEmail())), submitted, 0);

        long waitlisted = talk(speaker, "WORKSHOP");
        Api.body(api.put("/talks/" + waitlisted + "/status", "{\"status\":\"WAITLISTED\"}"), 200);
        refused(409, register(waitlisted, body("Ada", uniqueEmail())), waitlisted, 0);

        long rejected = talk(speaker, "WORKSHOP");
        Api.body(api.put("/talks/" + rejected + "/status", "{\"status\":\"REJECTED\"}"), 200);
        refused(409, register(rejected, body("Ada", uniqueEmail())), rejected, 0);

        long other = api.newSpeaker();
        long withdrawn = talk(other, "WORKSHOP");
        Api.body(api.post("/talks/" + withdrawn + "/withdraw", "{\"speakerId\":" + other + "}"), 200);
        refused(409, register(withdrawn, body("Ada", uniqueEmail())), withdrawn, 0);
    }

    @Test
    void r3_aWaitlistedWorkshopTakesRegistrationsOnceAccepted() {
        long speaker = api.newSpeaker();
        long w = talk(speaker, "WORKSHOP");
        Api.body(api.put("/talks/" + w + "/status", "{\"status\":\"WAITLISTED\"}"), 200);
        refused(409, register(w, body("Ada", uniqueEmail())), w, 0);
        api.rate(w, 1, 4);
        api.rate(w, 2, 4);
        Api.body(api.put("/talks/" + w + "/status", "{\"status\":\"ACCEPTED\"}"), 200);
        registered(w, "Ada", uniqueEmail());
    }

    // ------------------------------------------------------------ R4, D15, D20: withdrawn workshops keep their list

    @Test
    void r4d15_registrationsStayWhenTheWorkshopIsWithdrawn() {
        long speaker = api.newSpeaker();
        long w = accepted(speaker, "WORKSHOP");
        JsonNode first = registered(w, "Ada", "ada@example.com");
        JsonNode second = registered(w, "Grace", "grace@example.com");

        Api.body(api.post("/talks/" + w + "/withdraw", "{\"speakerId\":" + speaker + "}"), 200);

        JsonNode list = list(w);
        assertEquals(2, list.size());
        assertEquals(first, list.get(0));
        assertEquals(second, list.get(1));
        refused(409, register(w, body("Linus", uniqueEmail())), w, 2); // D8: no new ones
    }

    @Test
    void d15_listingAWorkshopThatIsNotAcceptedIs200() {
        long speaker = api.newSpeaker();
        long submitted = talk(speaker, "WORKSHOP");
        assertEquals(0, list(submitted).size());
        assertEquals("[]", api.get("/workshops/" + submitted + "/registrations").body().trim());

        long rejected = talk(speaker, "WORKSHOP");
        Api.body(api.put("/talks/" + rejected + "/status", "{\"status\":\"REJECTED\"}"), 200);
        assertEquals(0, list(rejected).size());
    }

    @Test
    void d20_aFullWorkshopStays409AfterItIsWithdrawn() {
        long speaker = api.newSpeaker();
        long w = accepted(speaker, "WORKSHOP");
        fill(w, 20);
        Api.body(api.post("/talks/" + w + "/withdraw", "{\"speakerId\":" + speaker + "}"), 200);
        refused(409, register(w, body("Late", uniqueEmail())), w, 20);
    }

    // ------------------------------------------------------------ R5, D10: capacity 20

    @Test
    void r5d10_theTwentyFirstRegistrationIs409() {
        long w = workshop();
        fill(w, 20);
        assertEquals(20, list(w).size());
        refused(409, register(w, body("Number 21", uniqueEmail())), w, 20);
    }

    @Test
    void r5_aRushNeverGetsMoreThanTwentySeats() {
        long w = workshop();
        List<CompletableFuture<HttpResponse<String>>> sent = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            sent.add(api.sendAsync("POST", "/workshops/" + w + "/registrations", body("Rush " + i, uniqueEmail())));
        }
        int created = 0;
        int refused = 0;
        for (CompletableFuture<HttpResponse<String>> f : sent) {
            HttpResponse<String> res = f.join();
            assertTrue(res.statusCode() == 201 || res.statusCode() == 409, res.statusCode() + " " + res.body());
            if (res.statusCode() == 201) {
                created++;
            } else {
                refused++;
            }
        }
        assertEquals(20, created);
        assertEquals(40, refused);
        JsonNode list = list(w);
        assertEquals(20, list.size());
        for (int i = 1; i < list.size(); i++) { // D16: id order
            assertTrue(list.get(i - 1).get("id").asLong() < list.get(i).get("id").asLong(), list.toString());
        }
    }

    @Test
    void r5_rushesOnTwoWorkshopsGetTwentyEach() {
        long a = workshop();
        long b = workshop();
        List<CompletableFuture<HttpResponse<String>>> sent = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            sent.add(api.sendAsync("POST", "/workshops/" + a + "/registrations", body("A" + i, uniqueEmail())));
            sent.add(api.sendAsync("POST", "/workshops/" + b + "/registrations", body("B" + i, uniqueEmail())));
        }
        for (CompletableFuture<HttpResponse<String>> f : sent) {
            int code = f.join().statusCode();
            assertTrue(code == 201 || code == 409, String.valueOf(code));
        }
        assertEquals(20, list(a).size());
        assertEquals(20, list(b).size());
    }

    // ------------------------------------------------------------ R6, D9, D12: one registration per email

    @Test
    void r6d9_theSameEmailInAnyCaseIs409AndTheFirstKeepsItsPlace() {
        long w = workshop();
        JsonNode first = registered(w, "Ada", "ada@example.com");
        registered(w, "Grace", "grace@example.com");

        refused(409, register(w, body("Ada Again", "ADA@Example.com")), w, 2);
        refused(409, register(w, body("Ada Again", "ada@example.com")), w, 2);
        refused(409, register(w, body("Ada Again", "  Ada@EXAMPLE.com ")), w, 2); // D12: trim for the compare

        JsonNode list = list(w);
        assertEquals(first, list.get(0));
        assertEquals("Ada", list.get(0).get("name").asString());
    }

    // D9 "surrounding whitespace ignored": tabs and line breaks too, not only spaces (review finding 1)
    @Test
    void d9d12_whitespaceOtherThanSpacesAroundTheEmailIsTheSamePerson() {
        long w = workshop();
        registered(w, "Ada", "ada@example.com");
        for (String padded : new String[] {"\\tada@example.com", "ada@example.com\\n", "\\r\\n ADA@example.com\\t "}) {
            refused(409, register(w, body("Ada Again", padded)), w, 1);
        }

        long v = workshop();
        registered(v, "Ada", "\\tada@example.com");
        refused(409, register(v, body("Ada Again", "ada@example.com")), v, 1);
    }

    // Concurrency backstop: the unique index compares emails the same way as the check (review finding 1)
    @Test
    void d12_theUniqueIndexAlsoIgnoresCaseAndSurroundingWhitespace() {
        long w = workshop();
        jdbc.update("INSERT INTO workshop_registration (talk_id, name, email) VALUES (?, 'Ada', ?)", w, "ada@example.com");
        for (String same : new String[] {"ADA@example.com", " ada@example.com", "\tada@example.com\n", "\r\nAda@Example.com"}) {
            assertThrows(DuplicateKeyException.class, () -> jdbc.update(
                    "INSERT INTO workshop_registration (talk_id, name, email) VALUES (?, 'Ada', ?)", w, same), same);
        }
        jdbc.update("INSERT INTO workshop_registration (talk_id, name, email) VALUES (?, 'Ada', ?)", w, "ada2@example.com");
        assertEquals(2, list(w).size());
    }

    @Test
    void r6_parallelDuplicatesGetOneSeat() {
        long w = workshop();
        String[] spellings = {"rush@example.com", "RUSH@example.com", "Rush@Example.com", "rush@EXAMPLE.COM",
                " rush@example.com", "rush@example.com ", "rUsH@eXaMpLe.CoM"};
        List<CompletableFuture<HttpResponse<String>>> sent = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            sent.add(api.sendAsync("POST", "/workshops/" + w + "/registrations",
                    body("Rush " + i, spellings[i % spellings.length])));
        }
        int created = 0;
        for (CompletableFuture<HttpResponse<String>> f : sent) {
            HttpResponse<String> res = f.join();
            assertTrue(res.statusCode() == 201 || res.statusCode() == 409, res.statusCode() + " " + res.body());
            if (res.statusCode() == 201) {
                created++;
            }
        }
        assertEquals(1, created);
        assertEquals(1, list(w).size());
    }

    // D11
    @Test
    void d11_aDuplicateOnAFullWorkshopIs409() {
        long w = workshop();
        registered(w, "Ada", "ada@example.com");
        fill(w, 19);
        refused(409, register(w, body("Ada", "ADA@example.com")), w, 20);
    }

    // ------------------------------------------------------------ R7: several workshops

    @Test
    void r7_theSamePersonCanRegisterForSeveralWorkshops() {
        long a = workshop();
        long b = workshop();
        registered(a, "Ada", "ada@example.com");
        JsonNode r = registered(b, "Ada", "ADA@example.com");
        assertEquals(b, r.get("workshopId").asLong());
        assertEquals(1, list(a).size());
        assertEquals(1, list(b).size());
    }

    // ------------------------------------------------------------ R8, D12, D13: as typed

    @Test
    void r8d12d13_emailAndNameAreReturnedAsTyped() {
        long w = workshop();
        JsonNode r = registered(w, "  Ada  Lovelace ", " ADA@Example.com ");
        assertEquals(" ADA@Example.com ", r.get("email").asString());
        assertEquals("  Ada  Lovelace ", r.get("name").asString());
        JsonNode listed = list(w).get(0);
        assertEquals(" ADA@Example.com ", listed.get("email").asString());
        assertEquals("  Ada  Lovelace ", listed.get("name").asString());
    }

    // ------------------------------------------------------------ R9, D16: registration order

    @Test
    void r9d16_theListIsInRegistrationOrder() {
        long w = workshop();
        JsonNode c = registered(w, "Charlie", "charlie@example.com");
        JsonNode a = registered(w, "Ada", "ada@example.com");
        JsonNode b = registered(w, "Bob", "bob@example.com");
        JsonNode list = list(w);
        assertEquals(3, list.size());
        assertEquals(c, list.get(0));
        assertEquals(a, list.get(1));
        assertEquals(b, list.get(2));
    }

    @Test
    void r9_theListOnlyHasThisWorkshopsRegistrations() {
        long a = workshop();
        long b = workshop();
        registered(a, "Ada", uniqueEmail());
        registered(b, "Bob", uniqueEmail());
        registered(a, "Cleo", uniqueEmail());
        JsonNode list = list(a);
        assertEquals(2, list.size());
        for (JsonNode r : list) {
            assertEquals(a, r.get("workshopId").asLong());
        }
    }

    // ------------------------------------------------------------ D1, D3: path

    @Test
    void d1_unknownTalkIs404() {
        assertEquals(404, register(999999, body("Ada", uniqueEmail())).statusCode());
        assertEquals(404, api.get("/workshops/999999/registrations").statusCode());
        assertEquals(404, api.get("/workshops/99999999999/registrations").statusCode());
    }

    @Test
    void d3_aNonIntegerIdIs400() {
        assertEquals(400, api.post("/workshops/abc/registrations", body("Ada", uniqueEmail())).statusCode());
        assertEquals(400, api.get("/workshops/abc/registrations").statusCode());
    }

    // ------------------------------------------------------------ D4, D5, D6, D7: body

    @Test
    void d4_missingEmptyOrUnreadableBodyIs400() {
        long w = workshop();
        refused(400, register(w, null), w, 0);
        refused(400, register(w, ""), w, 0);
        refused(400, register(w, "not json"), w, 0);
        refused(400, register(w, "[]"), w, 0);
        refused(400, register(w, "\"Ada\""), w, 0);
        refused(400, register(w, "{\"name\":\"Ada\","), w, 0);
    }

    @Test
    void d5_nameIsRequiredAndMustBeAString() {
        long w = workshop();
        String email = "\"email\":\"" + uniqueEmail() + "\"";
        String[] bad = {
                "{" + email + "}",
                "{\"name\":null," + email + "}",
                "{\"name\":\"\"," + email + "}",
                "{\"name\":\"   \"," + email + "}",
                "{\"name\":42," + email + "}",
                "{\"name\":true," + email + "}",
                "{\"name\":[\"Ada\"]," + email + "}",
                "{\"name\":{\"first\":\"Ada\"}," + email + "}",
        };
        for (String b : bad) {
            HttpResponse<String> res = register(w, b);
            assertEquals(400, res.statusCode(), b + ": " + res.body());
        }
        assertEquals(0, list(w).size());
    }

    @Test
    void d6_emailIsRequiredAndMustBeAString() {
        long w = workshop();
        String[] bad = {
                "{\"name\":\"Ada\"}",
                "{\"name\":\"Ada\",\"email\":null}",
                "{\"name\":\"Ada\",\"email\":\"\"}",
                "{\"name\":\"Ada\",\"email\":\"   \"}",
                "{\"name\":\"Ada\",\"email\":42}",
                "{\"name\":\"Ada\",\"email\":[\"ada@example.com\"]}",
        };
        for (String b : bad) {
            HttpResponse<String> res = register(w, b);
            assertEquals(400, res.statusCode(), b + ": " + res.body());
        }
        assertEquals(0, list(w).size());
    }

    @Test
    void d7_anEmailThatIsNotAnEmailIs400() {
        long w = workshop();
        for (String email : new String[] {"ada", "ada@example", "ada.lovelace@example", "example.com@ada"}) {
            HttpResponse<String> res = register(w, body("Ada", email));
            assertEquals(400, res.statusCode(), email + ": " + res.body());
        }
        assertEquals(0, list(w).size());
    }

    // D14
    @Test
    void d14_unknownFieldsAreIgnored() {
        long w = workshop();
        JsonNode r = Api.body(register(w, "{\"name\":\"Ada\",\"email\":\"ada@example.com\",\"phone\":\"123\"}"), 201);
        assertEquals(4, r.size(), r.toString());
        assertEquals(1, list(w).size());
    }

    // D17
    @Test
    void d17_theWorkshopsOwnSpeakersCanRegister() {
        long speaker = api.newSpeaker();
        long co = api.newSpeaker();
        long w = accepted(speaker, "WORKSHOP");
        Api.body(api.put("/talks/" + w + "/co-speakers/" + co, null), 200);
        String speakerEmail = Api.body(api.get("/speakers/" + speaker), 200).get("email").asString();
        String coEmail = Api.body(api.get("/speakers/" + co), 200).get("email").asString();
        registered(w, "Speaker", speakerEmail);
        registered(w, "Co-speaker", coEmail);
        fill(w, 18);
        refused(409, register(w, body("Late", uniqueEmail())), w, 20); // they count towards the 20
    }

    // ------------------------------------------------------------ D18: order of checks

    @Test
    void d18_pathBeatsBodyBeatsState() {
        long speaker = api.newSpeaker();
        long presentation = accepted(speaker, "PRESENTATION");
        long submitted = talk(speaker, "WORKSHOP");
        long full = workshop();
        fill(full, 20);

        assertEquals(404, register(999999, "not json").statusCode());
        assertEquals(404, register(999999, "{}").statusCode());
        assertEquals(400, register(presentation, "not json").statusCode());
        assertEquals(400, register(presentation, null).statusCode());
        assertEquals(409, register(presentation, body("Ada", uniqueEmail())).statusCode());
        refused(400, register(submitted, "{\"name\":\"Ada\"}"), submitted, 0);
        refused(400, register(full, "{\"name\":\"Ada\",\"email\":\"nope\"}"), full, 20);
    }

    // D19
    @Test
    void d19_thereIsNoCancelling() {
        long w = workshop();
        registered(w, "Ada", "ada@example.com");
        assertEquals(405, api.send("DELETE", "/workshops/" + w + "/registrations", null).statusCode());
        assertEquals(1, list(w).size());
    }

    // ------------------------------------------------------------ Concurrency: registrations racing a withdrawal

    @Test
    void concurrency_aRegistrationRacingAWithdrawalIsEitherStoredOr409() {
        long speaker = api.newSpeaker();
        long w = accepted(speaker, "WORKSHOP");
        List<CompletableFuture<HttpResponse<String>>> sent = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            sent.add(api.sendAsync("POST", "/workshops/" + w + "/registrations", body("Rush " + i, uniqueEmail())));
            if (i == 7) {
                sent.add(api.sendAsync("POST", "/talks/" + w + "/withdraw", "{\"speakerId\":" + speaker + "}"));
            }
        }
        int created = 0;
        for (CompletableFuture<HttpResponse<String>> f : sent) {
            HttpResponse<String> res = f.join();
            if (res.request().uri().getPath().endsWith("/withdraw")) {
                assertEquals(200, res.statusCode(), res.body());
                continue;
            }
            assertTrue(res.statusCode() == 201 || res.statusCode() == 409, res.statusCode() + " " + res.body());
            if (res.statusCode() == 201) {
                created++;
            }
        }
        assertEquals(created, list(w).size());
        refused(409, register(w, body("After", uniqueEmail())), w, created);
    }
}
