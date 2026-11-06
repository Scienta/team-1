package no.scienta.cfp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Date;

import org.junit.jupiter.api.Test;

class UtilsTest {

    @Test
    void isEmpty() {
        assertTrue(Utils.isEmpty(null));
        assertTrue(Utils.isEmpty(""));
        assertTrue(Utils.isEmpty("   "));
        assertFalse(Utils.isEmpty("a"));
    }

    @Test
    void isNumeric() {
        assertTrue(Utils.isNumeric("123"));
        assertFalse(Utils.isNumeric("12a"));
        assertFalse(Utils.isNumeric(""));
    }

    @Test
    void capitalize() {
        assertEquals("Workshop", Utils.capitalize("WORKSHOP"));
    }

    // No longer used by /admin/stats (ratings-cr D8: 2 decimals half even). A talk's averageRating is trimmed and
    // rounded half even to 2 decimals (ConferenceService.calculateAverage, tested in RatingsCrTest).
    @Test
    void roundRatingCutsOffForTheAdminStatistics() {
        assertEquals(4.2, Utils.roundRating(4.25));
        assertEquals(3.6, Utils.roundRating(3.6666));
        assertEquals(4.5, Utils.roundRating(4.5));
    }

    // ratedAtDisplay (specs/ratings/spec.md): UTC, no leading zeros, 12-hour clock, one ordinary space before AM/PM.
    @Test
    void usShortIsTheLegacyReviewerFormatInUtc() {
        assertEquals("11/4/26, 1:05 PM", Utils.usShort(utc("2026-11-04T13:05:59Z")));
        assertEquals("11/4/26, 12:00 AM", Utils.usShort(utc("2026-11-04T00:00:00Z")));
        assertEquals("11/4/26, 12:30 PM", Utils.usShort(utc("2026-11-04T12:30:00Z")));
        assertEquals("1/2/27, 9:07 AM", Utils.usShort(utc("2027-01-02T09:07:00Z")));
        assertEquals("12/31/26, 11:59 PM", Utils.usShort(utc("2026-12-31T23:59:00Z")));
    }

    @Test
    void usShortIsPlainAsciiOnEveryJdk() {
        String shown = Utils.usShort(utc("2026-11-04T13:05:00Z"));
        assertTrue(shown.chars().allMatch(c -> c < 128), "non-ASCII character in " + shown);
    }

    @Test
    void nowParsesBackInTheDatabaseFormat() {
        String now = Utils.now();
        assertTrue(now.matches("\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d"), now);
        assertEquals(utc("2026-11-04T13:05:09Z"), Utils.parse("2026-11-04 13:05:09"));
        long drift = Math.abs(System.currentTimeMillis() - Utils.parse(now).getTime());
        assertTrue(drift < 60_000, "now() is " + drift + " ms off the clock");
    }

    // D11: the committee spreadsheet's date (/admin/talks.csv), Oslo time, as Java 11 rendered it.
    @Test
    void norwegianDateIsTheCommitteeFormat() {
        assertEquals("4. nov. 2026", Utils.norwegianDate(utc("2026-11-04T13:05:00Z")));
        assertEquals("1. jan. 2027", Utils.norwegianDate(utc("2026-12-31T23:30:00Z")));
        assertEquals("15. mai 2026", Utils.norwegianDate(utc("2026-05-15T10:00:00Z")));
    }

    private static Date utc(String instant) {
        return Date.from(Instant.parse(instant));
    }
}
