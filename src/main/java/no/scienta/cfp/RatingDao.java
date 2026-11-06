package no.scienta.cfp;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RatingDao {

    private final JdbcTemplate jdbc;

    public RatingDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Insert or replace the reviewer's rating. One statement, so two clicks at once can't make two rows.
     */
    public void save(long talkId, long reviewerId, int score, String ratedAt) {
        jdbc.update("INSERT INTO rating (talk_id, reviewer_id, score, rated_at) VALUES (?, ?, ?, ?) "
                + "ON CONFLICT (talk_id, reviewer_id) DO UPDATE SET score = excluded.score, rated_at = excluded.rated_at",
                talkId, reviewerId, score, ratedAt);
    }

    /**
     * Returns {cnt, total, lo, hi} for one talk.
     */
    public Map<String, Object> stats(long talkId) {
        return jdbc.queryForMap("SELECT COUNT(*) AS cnt, COALESCE(SUM(score), 0) AS total, COALESCE(MIN(score), 0) AS lo, COALESCE(MAX(score), 0) AS hi FROM rating WHERE talk_id = ?", talkId);
    }

    /**
     * Returns {cnt, total, lo, hi} by talk id for the given talks, in one query. Talks without ratings aren't in the map.
     */
    public Map<Long, Map<String, Object>> statsByTalk(Collection<Long> talkIds) {
        Map<Long, Map<String, Object>> stats = new HashMap<>();
        if (talkIds.isEmpty()) {
            return stats;
        }
        jdbc.query("SELECT talk_id, COUNT(*) AS cnt, SUM(score) AS total, MIN(score) AS lo, MAX(score) AS hi FROM rating WHERE talk_id = ANY(?) GROUP BY talk_id",
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("bigint", talkIds.toArray())),
                rs -> {
                    Map<String, Object> row = new HashMap<>();
                    row.put("cnt", rs.getLong("cnt"));
                    row.put("total", rs.getLong("total"));
                    row.put("lo", rs.getLong("lo"));
                    row.put("hi", rs.getLong("hi"));
                    stats.put(rs.getLong("talk_id"), row);
                });
        return stats;
    }

    // public double average(long talkId) {
    //     return jdbc.queryForObject("SELECT AVG(score) FROM rating WHERE talk_id = ?", Double.class, talkId);
    // }
}
