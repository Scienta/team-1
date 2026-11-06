package no.scienta.cfp;

import java.sql.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ProgrammeDao {

    private final JdbcTemplate jdbc;

    private final RowMapper<OccupiedCell> mapper = (rs, rowNum) ->
            new OccupiedCell(rs.getString("room"), rs.getString("slot"), rs.getLong("talk_id"));

    public ProgrammeDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Locks the fixed slot row so concurrent programme edits for this slot run one after another (R8). */
    public void lockSlot(Venue.Slot slot) {
        List<String> locked = jdbc.queryForList(
                "SELECT slot FROM programme_slot WHERE slot = ? FOR UPDATE", String.class, slot.name());
        if (locked.size() != 1) {
            throw new IllegalStateException("expected one programme_slot row for " + slot.name()
                    + ", got " + locked.size());
        }
    }

    public Optional<OccupiedCell> find(Venue.Room room, Venue.Slot slot) {
        List<OccupiedCell> list = jdbc.query(
                "SELECT room, slot, talk_id FROM programme WHERE room = ? AND slot = ?",
                mapper, room.name(), slot.name());
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public Optional<OccupiedCell> findByTalkId(long talkId) {
        List<OccupiedCell> list = jdbc.query(
                "SELECT room, slot, talk_id FROM programme WHERE talk_id = ?", mapper, talkId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /** All occupied cells in the slot, locked for the rest of the transaction (R6, R8). */
    public List<OccupiedCell> lockOccupiedInSlot(Venue.Slot slot) {
        return jdbc.query(
                "SELECT room, slot, talk_id FROM programme WHERE slot = ? FOR UPDATE",
                mapper, slot.name());
    }

    public void insert(Venue.Room room, Venue.Slot slot, long talkId) {
        jdbc.update("INSERT INTO programme (room, slot, talk_id) VALUES (?, ?, ?)",
                room.name(), slot.name(), talkId);
    }

    public boolean delete(Venue.Room room, Venue.Slot slot) {
        return jdbc.update("DELETE FROM programme WHERE room = ? AND slot = ?", room.name(), slot.name()) > 0;
    }

    /**
     * Every occupied cell with its talk's current title, format and speaker names (specs/programme-export/spec.md R5,
     * R6), in one statement so a row never mixes before and after a concurrent change. Unordered: the caller sorts.
     * Speaker rows that don't exist are left out (D20); cells with an unknown room or slot are skipped.
     */
    public List<ProgrammeRow> exportRows() {
        List<ProgrammeRow> rows = new ArrayList<>();
        jdbc.query(
                "SELECT p.room, p.slot, p.talk_id, t.title, t.format, s.name AS main_name, "
                        + "  ARRAY(SELECT cs_s.name FROM co_speaker cs JOIN speaker cs_s ON cs_s.id = cs.speaker_id "
                        + "        WHERE cs.talk_id = t.id ORDER BY cs.id) AS co_names "
                        + "FROM programme p "
                        + "JOIN talk t ON t.id = p.talk_id "
                        + "LEFT JOIN speaker s ON s.id = t.speaker_id",
                rs -> {
                    Optional<Venue.Room> room = Venue.parseRoom(rs.getString("room"));
                    Optional<Venue.Slot> slot = Venue.parseSlot(rs.getString("slot"));
                    if (room.isEmpty() || slot.isEmpty()) {
                        return;
                    }
                    List<String> speakers = new ArrayList<>();
                    String main = rs.getString("main_name");
                    if (main != null) {
                        speakers.add(main);
                    }
                    Array co = rs.getArray("co_names");
                    if (co != null) {
                        speakers.addAll(Arrays.asList((String[]) co.getArray()));
                    }
                    rows.add(new ProgrammeRow(room.get(), slot.get(), rs.getLong("talk_id"),
                            rs.getString("title"), rs.getString("format"), speakers));
                });
        return rows;
    }

    static final class OccupiedCell {
        final String room;
        final String slot;
        final long talkId;

        OccupiedCell(String room, String slot, long talkId) {
            this.room = room;
            this.slot = slot;
            this.talkId = talkId;
        }
    }
}
