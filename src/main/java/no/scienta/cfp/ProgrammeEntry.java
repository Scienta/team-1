package no.scienta.cfp;

/** A talk placed in a room and slot (specs/scheduling/spec.md Entry). */
public class ProgrammeEntry {

    public String room;
    public String slot;
    public String start;
    public String end;
    public long talkId;

    public ProgrammeEntry() {
    }

    public ProgrammeEntry(Venue.Room room, Venue.Slot slot, long talkId) {
        this.room = room.name();
        this.slot = slot.name();
        this.start = slot.start;
        this.end = slot.end;
        this.talkId = talkId;
    }
}
