package no.scienta.cfp;

import java.util.List;

/** One scheduled talk in the programme export (specs/programme-export/spec.md R4). Field order is the JSON order. */
public class ProgrammeRow {

    public String slot;
    public String start;
    public String end;
    public String room;
    public long talkId;
    public String title;
    public String format;
    public List<String> speakers;

    public ProgrammeRow() {
    }

    ProgrammeRow(Venue.Room room, Venue.Slot slot, long talkId, String title, String format, List<String> speakers) {
        this.slot = slot.name();
        this.start = slot.start;
        this.end = slot.end;
        this.room = room.name();
        this.talkId = talkId;
        this.title = title;
        this.format = format;
        this.speakers = speakers;
    }
}
