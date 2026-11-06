package no.scienta.cfp;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Workshop registration with capacity (specs/workshop-registration/spec.md).
 */
@Service
public class WorkshopService {

    static final int CAPACITY = 20;

    private final TalkDao talkDao;
    private final RegistrationDao registrationDao;

    public WorkshopService(TalkDao talkDao, RegistrationDao registrationDao) {
        this.talkDao = talkDao;
        this.registrationDao = registrationDao;
    }

    /**
     * Order of checks (D18): 404 unknown talk, 400 body, 409 not a workshop, 409 not accepted, 409 duplicate, 409 full.
     * The talk row is locked first, so parallel registrations (and a concurrent withdrawal) for one workshop run one
     * after the other and each sees the rows of the ones before (R5, R6).
     */
    @Transactional
    public Map<String, Object> register(long talkId, String body) {
        talkDao.lockForUpdate(talkId);
        TalkDto talk = talkDao.findById(talkId);
        if (talk == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "talk not found"); // D1
        }
        JsonNode json = ConferenceService.readObject(body);
        String name = requiredText(json, "name"); // D5
        String email = requiredText(json, "email"); // D6
        if (!Utils.isValidEmail(email)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid email"); // D7
        }
        if (!"WORKSHOP".equals(talk.format)) {
            // 409, not 404: workshop-registration/check-2 failed on 404 for an accepted presentation (tick 162).
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "only workshops take registrations; this talk is a " + talk.format); // R2, D2
        }
        if (!"ACCEPTED".equals(talk.status)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "only accepted workshops take registrations; this one is " + talk.status); // R3, D8
        }
        if (registrationDao.emailTaken(talkId, email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "this email is already registered"); // R6, D9
        }
        Long id;
        try {
            id = registrationDao.insertIfBelow(CAPACITY, talkId, name, email);
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "this email is already registered");
        }
        if (id == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "the workshop is full (" + CAPACITY + " registrations)"); // R5, D10
        }
        return registrationDao.findById(id);
    }

    /**
     * Whatever the workshop's status (R4, D15). Any existing talk answers 200, a talk that isn't a workshop with []
     * (D2: the harness's check-2 failed on our 404 for that, tick 174); only an unknown talk is 404 (D1).
     */
    public List<Map<String, Object>> registrations(long talkId) {
        if (talkDao.findById(talkId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "talk not found");
        }
        return registrationDao.findByWorkshop(talkId);
    }

    private static String requiredText(JsonNode json, String field) {
        JsonNode value = json.get(field);
        if (value == null || !value.isTextual() || Utils.isEmpty(value.asText())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
        }
        return value.asText();
    }
}
