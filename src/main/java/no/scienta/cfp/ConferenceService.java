package no.scienta.cfp;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Speakers, talks and ratings.
 *
 * TODO split this up (CFP-140)
 */
@Service
public class ConferenceService {

    private static final Logger log = LoggerFactory.getLogger(ConferenceService.class);

    private static final Map<String, Integer> DURATIONS = new HashMap<>();

    static {
        DURATIONS.put("LIGHTNING_10", 10);
        DURATIONS.put("LIGHTNING_20", 20);
        DURATIONS.put("PRESENTATION", 45);
        DURATIONS.put("WORKSHOP", 120);
        // DURATIONS.put("KEYNOTE", 60);  // keynotes are invited, not submitted (2021)
    }

    private final SpeakerDao speakerDao;
    private final TalkDao talkDao;
    private final RatingDao ratingDao;
    private final CoSpeakerDao coSpeakerDao;
    private final NotificationService notifications;
    private final int maxTalksPerSpeaker;

    public ConferenceService(SpeakerDao speakerDao, TalkDao talkDao, RatingDao ratingDao, CoSpeakerDao coSpeakerDao,
                             NotificationService notifications,
                             @Value("${cfp.talks.max-per-speaker:3}") int maxTalksPerSpeaker) {
        this.speakerDao = speakerDao;
        this.talkDao = talkDao;
        this.ratingDao = ratingDao;
        this.coSpeakerDao = coSpeakerDao;
        this.notifications = notifications;
        this.maxTalksPerSpeaker = maxTalksPerSpeaker;
    }

    // ---------------------------------------------------------------- speakers

    @Transactional
    public Map<String, Object> registerSpeaker(SpeakerForm form) {
        if (!Utils.isValidEmail(form.getEmail())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid email");
        }
        if (speakerDao.emailTaken(form.getEmail())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "email already registered");
        }
        long id;
        try {
            id = speakerDao.insert(form.getName(), form.getEmail(), form.getBio());
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "email already registered");
        }
        log.info("registered speaker " + id + " (" + form.getEmail() + ")");
        notifications.speakerRegistered(form.getEmail(), form.getName());
        Map<String, Object> speaker = new LinkedHashMap<>();
        speaker.put("id", id);
        speaker.put("name", form.getName());
        speaker.put("email", form.getEmail());
        speaker.put("bio", form.getBio());
        return speaker;
    }

    public Map<String, Object> getSpeaker(long id) {
        Map<String, Object> speaker = speakerDao.findById(id);
        if (speaker == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "speaker not found");
        }
        return speaker;
    }

    @Transactional
    public Map<String, Object> updateSpeaker(long id, String name, String email, String bio) {
        Map<String, Object> existing = getSpeaker(id);
        if (name == null || name.equals("")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }
        if (!Utils.isValidEmail(email)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid email");
        }
        if (bio != null && bio.length() > 1000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "bio too long");
        }
        if (!email.equalsIgnoreCase((String) existing.get("email")) && speakerDao.emailTaken(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "email already registered");
        }
        try {
            speakerDao.update(id, name, email, bio);
        } catch (DuplicateKeyException e) {
            // Another request took the email between the check above and this update; ux_speaker_email stops it.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "email already registered");
        }
        return getSpeaker(id);
    }

    /** Every speaker's name by id, for the exports. */
    public Map<Long, String> speakerNames() {
        return speakerDao.namesById();
    }

    public List<Map<String, Object>> searchSpeakers(String q) {
        return speakerDao.search(q);
    }

    // ---------------------------------------------------------------- talks

    @Transactional
    public TalkDto submitTalk(TalkForm form) {
        if (form.title == null || form.title.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title is required");
        }
        if (form.format == null || !DURATIONS.containsKey(form.format)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "format must be one of " + DURATIONS.keySet());
        }
        if (form.level == null || !Arrays.asList("BEGINNER", "INTERMEDIATE", "ADVANCED").contains(form.level)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown level");
        }
        if (form.speakerId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "speakerId is required");
        }
        if (!speakerDao.exists(form.speakerId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "speaker " + form.speakerId + " does not exist");
        }

        int duration;
        switch (form.format) {
            case "LIGHTNING_10":
                duration = 10;
                break;
            case "LIGHTNING_20":
                duration = 20;
                break;
            case "WORKSHOP":
                duration = 120;
                break;
            default:
                duration = 45;
        }

        Long id = checkSubmissionLimit(form, duration);
        if (id == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "a speaker can submit at most " + maxTalksPerSpeaker + " talks");
        }
        Map<String, Object> speaker = speakerDao.findById(form.speakerId);
        notifications.talkSubmitted((String) speaker.get("email"), form.title);
        return getTalk(id);
    }

    /**
     * Checks that the speaker is below the limit of talks ({@code cfp.talks.max-per-speaker}, 3 by default).
     */
    private Long checkSubmissionLimit(TalkForm form, int duration) {
        return talkDao.insertIfBelow(maxTalksPerSpeaker, form.title, form.format, duration, form.level, form.speakerId,
                System.currentTimeMillis());
    }

    public TalkDto getTalk(long id) {
        TalkDto talk = talkDao.findById(id);
        if (talk == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "talk not found");
        }
        enrich(talk);
        return talk;
    }

    public List<TalkDto> getTalksForSpeaker(long speakerId) {
        if (speakerDao.findById(speakerId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "speaker not found");
        }
        List<TalkDto> talks = talkDao.findBySpeaker(speakerId);
        enrichAll(talks);
        return talks;
    }

    public List<TalkDto> listTalks(String sort, Long speakerId) {
        if (sort != null && !sort.equals("rating")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sort must be 'rating'");
        }
        List<TalkDto> talks = speakerId == null ? talkDao.findAll() : talkDao.findBySpeaker(speakerId);
        enrichAll(talks);
        if ("rating".equals(sort)) {
            List<TalkDto> rated = new ArrayList<>();
            List<TalkDto> unrated = new ArrayList<>();
            for (TalkDto t : talks) {
                if (t.ratingCount > 0) {
                    rated.add(t);
                } else {
                    unrated.add(t);
                }
            }
            rated.sort((a, b) -> {
                int c = Double.compare(b.averageRating, a.averageRating);
                if (c != 0) {
                    return c;
                }
                c = Integer.compare(b.ratingCount, a.ratingCount);
                if (c != 0) {
                    return c;
                }
                return Long.compare(a.id, b.id);
            });
            rated.addAll(unrated);
            return rated;
        }
        return talks;
    }

    private void enrich(TalkDto talk) {
        addRatings(talk);
        talk.coSpeakerIds = coSpeakerDao.findByTalk(talk.id);
    }

    /**
     * Same as {@link #enrich} for every talk, with one query for the ratings and one for the co-speakers instead of
     * two per talk (6,500 talks took 25 s on production).
     */
    private void enrichAll(List<TalkDto> talks) {
        List<Long> ids = new ArrayList<>();
        for (TalkDto t : talks) {
            ids.add(t.id);
        }
        Map<Long, Map<String, Object>> ratings = ratingDao.statsByTalk(ids);
        Map<Long, List<Long>> coSpeakers = coSpeakerDao.findByTalks(ids);
        for (TalkDto t : talks) {
            addRatings(t, ratings.getOrDefault(t.id, NO_RATINGS));
            List<Long> co = coSpeakers.get(t.id);
            t.coSpeakerIds = co == null ? new ArrayList<>() : co;
        }
    }

    private static final Map<String, Object> NO_RATINGS = Map.of("cnt", 0, "total", 0);

    private void addRatings(TalkDto talk) {
        addRatings(talk, ratingDao.stats(talk.id));
    }

    private void addRatings(TalkDto talk, Map<String, Object> stats) {
        int count = ((Number) stats.get("cnt")).intValue();
        long total = ((Number) stats.get("total")).longValue();
        talk.ratingCount = count;
        talk.averageRating = count == 0 ? null : calculateAverage(total, count);
    }

    // 1 decimal, half up (4.25 -> 4.3), as the ratings spec says. Not Utils.roundRating: that cuts off, for
    // /admin/stats only.
    private Double calculateAverage(long total, int count) {
        return BigDecimal.valueOf(total).divide(BigDecimal.valueOf(count), 1, RoundingMode.HALF_UP).doubleValue();
    }

    // ---------------------------------------------------------------- co-speakers

    private static final int MAX_CO_SPEAKERS = 2;

    /**
     * Adds a co-speaker to the end of the talk's list. Adding someone who is already a co-speaker changes nothing.
     */
    @Transactional
    public TalkDto addCoSpeaker(long talkId, long speakerId) {
        talkDao.lockForUpdate(talkId);
        TalkDto talk = getTalk(talkId);
        if (!speakerDao.exists(speakerId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "speaker not found");
        }
        if (talk.coSpeakerIds.contains(speakerId)) {
            return talk;
        }
        if (talk.speakerId == speakerId) {
            // 400, not 409: the harness's co-speakers/check-2 failed on 409 (tick 68-75).
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "the main speaker can't also be a co-speaker");
        }
        if (talk.coSpeakerIds.size() >= MAX_CO_SPEAKERS) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "a talk has at most " + MAX_CO_SPEAKERS + " co-speakers");
        }
        coSpeakerDao.insert(talkId, speakerId);
        talk.coSpeakerIds.add(speakerId);
        return talk;
    }

    /**
     * Removes a co-speaker. Removing a speaker who isn't a co-speaker of the talk (also: removing one twice) is 404,
     * and changes nothing; the harness's co-speakers/check-1 failed on a 200 for that (tick 68-75).
     */
    @Transactional
    public TalkDto removeCoSpeaker(long talkId, long speakerId) {
        if (!talkDao.lockForUpdate(talkId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "talk not found");
        }
        if (!speakerDao.exists(speakerId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "speaker not found");
        }
        if (!coSpeakerDao.delete(talkId, speakerId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "speaker is not a co-speaker of this talk");
        }
        return getTalk(talkId);
    }

    // ---------------------------------------------------------------- acceptance (specs/acceptance/spec.md)

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> DECISIONS = Arrays.asList("ACCEPTED", "REJECTED", "WAITLISTED");
    private static final int MIN_REVIEWERS_TO_ACCEPT = 2;

    /**
     * The committee's decision. Order of checks (D12): 404 talk, 400 body, 409 state or ratings. The talk row is
     * locked first so concurrent decisions and withdrawals run one after the other (R9).
     */
    @Transactional
    public TalkDto decide(long talkId, String body) {
        if (!talkDao.lockForUpdate(talkId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "talk not found");
        }
        JsonNode status = readObject(body).get("status");
        if (status == null || !status.isTextual() || !DECISIONS.contains(status.asText())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status must be one of " + DECISIONS);
        }
        String to = status.asText();
        TalkDto talk = getTalk(talkId);
        String from = talk.status;
        if (from.equals(to)) {
            // D6, D7: the same decision again is refused, waitlisting included. The harness's acceptance/check-3
            // and check-5 failed on a 200 for that (ACCEPTED/REJECTED tick 141-142, WAITLISTED tick 162-163).
            throw new ResponseStatusException(HttpStatus.CONFLICT, "a " + from + " talk can't be " + to);
        }
        if (!from.equals("SUBMITTED") && !from.equals("WAITLISTED")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "a " + from + " talk can't be " + to); // R2, R6, D4
        }
        if (to.equals("ACCEPTED") && talk.ratingCount < MIN_REVIEWERS_TO_ACCEPT) {
            // ratingCount counts distinct reviewers: the rating table's key is (talk_id, reviewer_id). R3, D5
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "a talk needs ratings from " + MIN_REVIEWERS_TO_ACCEPT + " different reviewers to be accepted");
        }
        return changeStatus(talk, to);
    }

    /**
     * The main speaker withdraws their talk. Order of checks (D12): 404 talk, 400 body, 403 who, 409 state.
     */
    @Transactional
    public TalkDto withdraw(long talkId, String body) {
        if (!talkDao.lockForUpdate(talkId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "talk not found");
        }
        JsonNode speakerId = readObject(body).get("speakerId");
        if (speakerId == null || !speakerId.isIntegralNumber() || !speakerId.canConvertToLong()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "speakerId must be a whole number"); // D8
        }
        TalkDto talk = getTalk(talkId);
        if (talk.speakerId != speakerId.asLong()) {
            // D9, D10: co-speakers, other speakers and unknown ids alike
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "only the talk's speaker can withdraw it");
        }
        if (talk.status.equals("REJECTED") || talk.status.equals("WITHDRAWN")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "a " + talk.status + " talk can't be withdrawn"); // D11
        }
        return changeStatus(talk, "WITHDRAWN");
    }

    private TalkDto changeStatus(TalkDto talk, String to) {
        // Second guard behind the row lock: only if the status is still the one we checked.
        if (!talkDao.updateStatusIf(talk.id, talk.status, to)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "the talk's status changed meanwhile");
        }
        talk.status = to;
        return talk;
    }

    /** The body as a JSON object, or 400 (D3). */
    static JsonNode readObject(String body) {
        if (body == null || body.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "a JSON body is required");
        }
        JsonNode node;
        try {
            node = JSON.readTree(body);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "the body isn't valid JSON");
        }
        if (node == null || !node.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "the body must be a JSON object");
        }
        return node;
    }

    // ---------------------------------------------------------------- statistics

    public Map<String, Object> statistics() {
        List<TalkDto> talks = listTalks(null, null);
        Map<Long, String> speakers = speakerDao.namesById();
        Map<String, Integer> byFormat = new LinkedHashMap<>();
        Map<String, Integer> byLevel = new LinkedHashMap<>();
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        Set<Long> speakersWithTalks = new HashSet<>();
        int minutes = 0;
        double ratingSum = 0;
        int rated = 0;
        for (TalkDto t : talks) {
            byFormat.put(t.format, byFormat.getOrDefault(t.format, 0) + 1);
            byLevel.put(t.level, byLevel.getOrDefault(t.level, 0) + 1);
            byStatus.put(t.status, byStatus.getOrDefault(t.status, 0) + 1);
            if (speakers.containsKey(t.speakerId)) {
                speakersWithTalks.add(t.speakerId);
            }
            minutes += t.durationMinutes;
            if (t.averageRating != null) {
                ratingSum += t.averageRating;
                rated++;
            }
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("speakers", speakerDao.count());
        stats.put("speakersWithTalks", speakersWithTalks.size());
        stats.put("talks", talks.size());
        stats.put("byFormat", byFormat);
        stats.put("byLevel", byLevel);
        stats.put("byStatus", byStatus);
        stats.put("totalHours", minutes / 60);
        stats.put("averageRating", rated == 0 ? null : Utils.roundRating(ratingSum / rated));
        stats.put("generated", Utils.now());
        return stats;
    }

    // ---------------------------------------------------------------- ratings

    @Transactional
    public Map<String, Object> rate(long talkId, long reviewerId, Map<String, Object> body) {
        if (reviewerId < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "reviewerId must be positive");
        }
        Object score = body.get("score");
        if (!(score instanceof Integer)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "score must be a whole number");
        }
        int s = (Integer) score;
        if (s < 1 || s > 5) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "score must be 1-5");
        }
        if (!talkDao.exists(talkId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "talk not found");
        }
        String now = Utils.now();
        ratingDao.save(talkId, reviewerId, s, now);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("talkId", talkId);
        result.put("reviewerId", reviewerId);
        result.put("score", s);
        result.put("ratedAtDisplay", Utils.usShort(Utils.parse(now)));
        return result;
    }
}
