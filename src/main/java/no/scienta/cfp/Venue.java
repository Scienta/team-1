package no.scienta.cfp;

import java.util.Optional;

/** Fixed rooms and slots for the programme (specs/scheduling/spec.md R1). */
final class Venue {

    enum Room {
        R1, R2, R3
    }

    enum Slot {
        S1("09:00", "10:00"),
        S2("10:15", "11:15"),
        S3("11:30", "12:30"),
        S4("13:30", "15:30"),
        S5("15:45", "16:45");

        final String start;
        final String end;

        Slot(String start, String end) {
            this.start = start;
            this.end = end;
        }

        boolean isLongSlot() {
            return this == S4;
        }
    }

    private Venue() {
    }

    static Optional<Room> parseRoom(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Room.valueOf(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    static Optional<Slot> parseSlot(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Slot.valueOf(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
