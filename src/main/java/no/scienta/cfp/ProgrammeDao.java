package no.scienta.cfp;

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
        jdbc.queryForList("SELECT slot FROM programme_slot WHERE slot = ? FOR UPDATE", slot.name());
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
