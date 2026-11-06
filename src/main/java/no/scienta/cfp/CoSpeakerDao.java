package no.scienta.cfp;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CoSpeakerDao {

    private final JdbcTemplate jdbc;

    public CoSpeakerDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The co-speakers' ids, in the order they were added.
     */
    public List<Long> findByTalk(long talkId) {
        return jdbc.queryForList("SELECT speaker_id FROM co_speaker WHERE talk_id = ? ORDER BY id", Long.class, talkId);
    }

    /**
     * The co-speakers' ids by talk id for the given talks, each in the order they were added, in one query. Talks
     * without co-speakers aren't in the map.
     */
    public Map<Long, List<Long>> findByTalks(Collection<Long> talkIds) {
        Map<Long, List<Long>> byTalk = new HashMap<>();
        if (talkIds.isEmpty()) {
            return byTalk;
        }
        jdbc.query("SELECT talk_id, speaker_id FROM co_speaker WHERE talk_id = ANY(?) ORDER BY talk_id, id",
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("bigint", talkIds.toArray())),
                rs -> {
                    byTalk.computeIfAbsent(rs.getLong("talk_id"), k -> new ArrayList<>()).add(rs.getLong("speaker_id"));
                });
        return byTalk;
    }

    public void insert(long talkId, long speakerId) {
        jdbc.update("INSERT INTO co_speaker (talk_id, speaker_id) VALUES (?, ?) ON CONFLICT (talk_id, speaker_id) DO NOTHING",
                talkId, speakerId);
    }

    /**
     * Returns whether there was such a co-speaker to remove.
     */
    public boolean delete(long talkId, long speakerId) {
        return jdbc.update("DELETE FROM co_speaker WHERE talk_id = ? AND speaker_id = ?", talkId, speakerId) > 0;
    }
}
