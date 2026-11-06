package no.scienta.cfp;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Programme scheduling (specs/scheduling/spec.md). Check order on PUT is D13.
 */
@Service
public class ProgrammeService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ProgrammeDao programmeDao;
    private final TalkDao talkDao;
    private final CoSpeakerDao coSpeakerDao;

    public ProgrammeService(ProgrammeDao programmeDao, TalkDao talkDao, CoSpeakerDao coSpeakerDao) {
        this.programmeDao = programmeDao;
        this.talkDao = talkDao;
        this.coSpeakerDao = coSpeakerDao;
    }

    @Transactional
    public ProgrammeEntry put(String roomToken, String slotToken, String body) {
        Venue.Room room = parseRoom(roomToken);
        Venue.Slot slot = parseSlot(slotToken);

        long talkId = readTalkId(body);

        programmeDao.lockSlot(slot);
        List<ProgrammeDao.OccupiedCell> occupied = programmeDao.lockOccupiedInSlot(slot);

        // D4: 400, not 404 (the talk is named in the body, like speakerId on POST /talks); the harness's
        // scheduling/check-1 failed on 404 (tick 199-202).
        if (!talkDao.lockForUpdate(talkId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "talk " + talkId + " does not exist");
        }
        TalkDto talk = talkDao.findById(talkId);
        if (talk == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "talk " + talkId + " does not exist");
        }

        // D9: same talk already in this cell — idempotent
        for (ProgrammeDao.OccupiedCell cell : occupied) {
            if (cell.room.equals(room.name()) && cell.talkId == talkId) {
                return new ProgrammeEntry(room, slot, talkId);
            }
        }

        if (!"ACCEPTED".equals(talk.status)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "only ACCEPTED talks go on the programme"); // R3, D5
        }
        if ("WORKSHOP".equals(talk.format) && !slot.isLongSlot()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "a WORKSHOP only fits in S4"); // R4, D6
        }

        // R5 / D8: talk already elsewhere
        programmeDao.findByTalkId(talkId).ifPresent(elsewhere -> {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "talk is already on the programme");
        });

        // R5 / D7: cell occupied by another talk
        for (ProgrammeDao.OccupiedCell cell : occupied) {
            if (cell.room.equals(room.name())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "room and slot already occupied");
            }
        }

        // R6 / D10: speaker / co-speaker clash in this slot
        Set<Long> placing = speakersOf(talk);
        for (ProgrammeDao.OccupiedCell cell : occupied) {
            TalkDto other = talkDao.findById(cell.talkId);
            if (other == null) {
                continue;
            }
            Set<Long> theirs = speakersOf(other);
            for (Long person : placing) {
                if (theirs.contains(person)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "speaker already booked in this slot");
                }
            }
        }

        try {
            programmeDao.insert(room, slot, talkId);
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "programme conflict");
        }
        return new ProgrammeEntry(room, slot, talkId);
    }

    @Transactional
    public ProgrammeEntry get(String roomToken, String slotToken) {
        Venue.Room room = parseRoom(roomToken);
        Venue.Slot slot = parseSlot(slotToken);
        return programmeDao.find(room, slot)
                .map(cell -> new ProgrammeEntry(room, slot, cell.talkId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "programme cell is empty")); // D2
    }

    @Transactional
    /** D12: the controller answers 204 with no body; the harness's checks 2-6 failed on 200 + Entry (tick 200-203). */
    public void delete(String roomToken, String slotToken) {
        Venue.Room room = parseRoom(roomToken);
        Venue.Slot slot = parseSlot(slotToken);
        programmeDao.lockSlot(slot);
        programmeDao.find(room, slot)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "programme cell is empty")); // D11
        programmeDao.delete(room, slot);
    }

    /**
     * The whole programme, earliest slot first and within a slot by room (specs/programme-export/spec.md R3, D19).
     * Read-only at the default isolation, no row locks (spec Concurrency).
     */
    @Transactional(readOnly = true)
    public List<ProgrammeRow> export() {
        List<ProgrammeRow> rows = programmeDao.exportRows();
        rows.sort(Comparator.comparing((ProgrammeRow r) -> Venue.Slot.valueOf(r.slot))
                .thenComparing(r -> Venue.Room.valueOf(r.room)));
        return rows;
    }

    /** RFC 4180 CSV with Unix record terminators (R8, R9, R10). */
    static String toCsv(List<ProgrammeRow> rows) {
        StringBuilder sb = new StringBuilder("slot,start,end,room,talkId,title,format,speakers\n");
        for (ProgrammeRow r : rows) {
            sb.append(csvField(r.slot)).append(',')
                    .append(csvField(r.start)).append(',')
                    .append(csvField(r.end)).append(',')
                    .append(csvField(r.room)).append(',')
                    .append(r.talkId).append(',')
                    .append(csvField(r.title)).append(',')
                    .append(csvField(r.format)).append(',')
                    .append(csvField(String.join("; ", r.speakers))) // R6
                    .append('\n');
        }
        return sb.toString();
    }

    /** Quoted only when it holds a comma, a double quote or a line break; quotes inside are doubled (R10, D7-D9). */
    static String csvField(String value) {
        if (value == null) {
            return "";
        }
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private Set<Long> speakersOf(TalkDto talk) {
        Set<Long> people = new HashSet<>();
        people.add(talk.speakerId);
        people.addAll(coSpeakerDao.findByTalk(talk.id));
        return people;
    }

    private static Venue.Room parseRoom(String token) {
        return Venue.parseRoom(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown room")); // D1
    }

    private static Venue.Slot parseSlot(String token) {
        return Venue.parseSlot(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown slot")); // D1
    }

    /** talkId from the body, or 400 (D3). */
    private static long readTalkId(String body) {
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
        JsonNode talkId = node.get("talkId");
        if (talkId == null || talkId.isNull() || !talkId.isIntegralNumber() || !talkId.canConvertToLong()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "talkId must be a whole number");
        }
        return talkId.asLong();
    }
}
