package no.scienta.cfp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ConferenceServiceTest {

    private SpeakerDao speakerDao;
    private TalkDao talkDao;
    private RatingDao ratingDao;
    private ConferenceService service;

    @BeforeEach
    void setUp() {
        speakerDao = mock(SpeakerDao.class);
        talkDao = mock(TalkDao.class);
        ratingDao = mock(RatingDao.class);
        service = new ConferenceService(speakerDao, talkDao, ratingDao, mock(CoSpeakerDao.class),
                mock(NotificationService.class), 3);
    }

    @Test
    void talkWithoutTitleIsRejected() {
        TalkForm form = form();
        form.title = " ";
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> service.submitTalk(form));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
    }

    @Test
    void unknownFormatIsRejected() {
        TalkForm form = form();
        form.format = "KEYNOTE";
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> service.submitTalk(form));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
    }

    @Test
    void unknownSpeakerIsRejected() {
        when(speakerDao.exists(7L)).thenReturn(false);
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> service.submitTalk(form()));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
    }

    @Test
    void theTalkLimitComesFromConfiguration() {
        service = new ConferenceService(speakerDao, talkDao, ratingDao, mock(CoSpeakerDao.class),
                mock(NotificationService.class), 5);
        when(speakerDao.exists(7L)).thenReturn(true);
        when(talkDao.insertIfBelow(eq(5), anyString(), anyString(), anyInt(), anyString(), anyLong(), anyLong()))
                .thenReturn(null);
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> service.submitTalk(form()));
        verify(talkDao).insertIfBelow(eq(5), anyString(), anyString(), anyInt(), anyString(), anyLong(), anyLong());
        assertEquals(HttpStatus.CONFLICT, e.getStatus());
        assertEquals("a speaker can submit at most 5 talks", e.getReason());
    }

    @Test
    void emailRegisteredConcurrentlyIsConflictInAnyServerLanguage() {
        SpeakerForm form = new SpeakerForm();
        form.setName("Ada");
        form.setEmail("ada@example.com");
        when(speakerDao.emailTaken("ada@example.com")).thenReturn(false);
        when(speakerDao.insert("Ada", "ada@example.com", null))
                .thenThrow(new DuplicateKeyException("FEIL: duplisert nøkkelverdi bryter unik-skranke"));
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> service.registerSpeaker(form));
        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
    }

    @Test
    void duplicateEmailIsConflict() {
        SpeakerForm form = new SpeakerForm();
        form.setName("Ada");
        form.setEmail("ada@example.com");
        when(speakerDao.emailTaken("ada@example.com")).thenReturn(true);
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> service.registerSpeaker(form));
        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
    }

    private TalkForm form() {
        TalkForm form = new TalkForm();
        form.title = "Legacy is a feeling";
        form.format = "PRESENTATION";
        form.level = "INTERMEDIATE";
        form.speakerId = 7L;
        return form;
    }
}
