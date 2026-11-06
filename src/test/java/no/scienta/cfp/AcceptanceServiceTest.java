package no.scienta.cfp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Acceptance rules that the HTTP API can't reach: no endpoint removes ratings, so an accepted talk with fewer than two
 * reviewers can only be set up with mocks.
 */
class AcceptanceServiceTest {

    // D7: accepting an already accepted talk is 200, unchanged, with no ratings check
    @Test
    void d7_acceptingAnAcceptedTalkAgainSkipsTheRatingsCheck() {
        TalkDao talkDao = mock(TalkDao.class);
        RatingDao ratingDao = mock(RatingDao.class);
        CoSpeakerDao coSpeakerDao = mock(CoSpeakerDao.class);
        ConferenceService service = new ConferenceService(mock(SpeakerDao.class), talkDao, ratingDao, coSpeakerDao,
                mock(NotificationService.class), 3);

        TalkDto talk = new TalkDto();
        talk.id = 1L;
        talk.speakerId = 7L;
        talk.status = "ACCEPTED";
        Map<String, Object> noRatings = new HashMap<>();
        noRatings.put("cnt", 0L);
        noRatings.put("total", 0L);
        when(talkDao.lockForUpdate(1L)).thenReturn(true);
        when(talkDao.findById(1L)).thenReturn(talk);
        when(ratingDao.stats(1L)).thenReturn(noRatings);
        when(coSpeakerDao.findByTalk(1L)).thenReturn(Collections.emptyList());

        TalkDto result = service.decide(1L, "{\"status\":\"ACCEPTED\"}");

        assertEquals("ACCEPTED", result.status);
        assertEquals(0, result.ratingCount);
        verify(talkDao, never()).updateStatusIf(anyLong(), anyString(), anyString());
    }
}
