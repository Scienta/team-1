package no.scienta.cfp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Workshop registrations (specs/workshop-registration/spec.md). Emails are stored as typed and compared as
 * {@code lower(trim(email))}, in SQL on both sides so the check and the unique index agree.
 */
@Repository
public class RegistrationDao {

    private final JdbcTemplate jdbc;

    private final RowMapper<Map<String, Object>> mapper = (rs, rowNum) -> {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", rs.getLong("id"));
        r.put("workshopId", rs.getLong("talk_id"));
        r.put("name", rs.getString("name"));
        r.put("email", rs.getString("email"));
        return r;
    };

    public RegistrationDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The workshop's registrations, first registered first.
     */
    public List<Map<String, Object>> findByWorkshop(long talkId) {
        return jdbc.query("SELECT id, talk_id, name, email FROM workshop_registration WHERE talk_id = ? ORDER BY id",
                mapper, talkId);
    }

    public Map<String, Object> findById(long id) {
        List<Map<String, Object>> list = jdbc.query(
                "SELECT id, talk_id, name, email FROM workshop_registration WHERE id = ?", mapper, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public boolean emailTaken(long talkId, String email) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM workshop_registration "
                + "WHERE talk_id = ? AND lower(trim(email)) = lower(trim(?))", Integer.class, talkId, email);
        return n != null && n > 0;
    }

    public int count(long talkId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM workshop_registration WHERE talk_id = ?", Integer.class,
                talkId);
        return n == null ? 0 : n;
    }

    /**
     * Inserts the registration only if the workshop has fewer than {@code max}. Returns the new id, or null. The
     * caller holds the talk's row lock; this is the second guard behind it.
     */
    public Long insertIfBelow(int max, long talkId, String name, String email) {
        List<Long> ids = jdbc.queryForList(
                "INSERT INTO workshop_registration (talk_id, name, email) SELECT ?, ?, ? "
                        + "WHERE (SELECT COUNT(*) FROM workshop_registration WHERE talk_id = ?) < ? RETURNING id",
                Long.class, talkId, name, email, talkId, max);
        return ids.isEmpty() ? null : ids.get(0);
    }
}
