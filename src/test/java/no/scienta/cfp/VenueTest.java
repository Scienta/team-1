package no.scienta.cfp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Fixed rooms and slots (specs/scheduling/spec.md R1). */
class VenueTest {

    @Test
    void r1_slotTimesMatchTheVenueTable() {
        assertEquals("09:00", Venue.Slot.S1.start);
        assertEquals("10:00", Venue.Slot.S1.end);
        assertEquals("10:15", Venue.Slot.S2.start);
        assertEquals("11:15", Venue.Slot.S2.end);
        assertEquals("11:30", Venue.Slot.S3.start);
        assertEquals("12:30", Venue.Slot.S3.end);
        assertEquals("13:30", Venue.Slot.S4.start);
        assertEquals("15:30", Venue.Slot.S4.end);
        assertEquals("15:45", Venue.Slot.S5.start);
        assertEquals("16:45", Venue.Slot.S5.end);
        assertEquals(5, Venue.Slot.values().length);
        assertEquals(3, Venue.Room.values().length);
    }

    @Test
    void d1_rejectsUnknownOrWrongCaseRoomAndSlot() {
        assertFalse(Venue.parseRoom("r1").isPresent());
        assertFalse(Venue.parseRoom("R4").isPresent());
        assertFalse(Venue.parseRoom("").isPresent());
        assertFalse(Venue.parseRoom(null).isPresent());
        assertFalse(Venue.parseSlot("s1").isPresent());
        assertFalse(Venue.parseSlot("S6").isPresent());
        assertFalse(Venue.parseSlot("").isPresent());
        assertFalse(Venue.parseSlot(null).isPresent());
        assertTrue(Venue.parseRoom("R1").isPresent());
        assertTrue(Venue.parseSlot("S4").isPresent());
    }
}
