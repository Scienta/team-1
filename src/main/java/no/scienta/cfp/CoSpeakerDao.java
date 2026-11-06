package no.scienta.cfp;

import java.util.List;

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
