package no.scienta.cfp;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * A talk as the API returns it. Also used internally all over the place.
 */
public class TalkDto {

    static final String SUBMITTED_AT = "yyyy-MM-dd'T'HH:mm:ss.SSS'+00:00'";

    public Long id;
    public String title;
    public String format;
    public String level;
    public Long speakerId;
    public Integer durationMinutes;
    public String status;
    // Exactly yyyy-MM-dd'T'HH:mm:ss.SSS+00:00 in UTC, as Jackson 2 wrote it (Jackson 3 would write Z).
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = SUBMITTED_AT, timezone = "UTC")
    public Date submittedAt;
    // yyyy-MM-dd, its toString(), as Jackson 2 wrote it (Jackson 3 would write a full timestamp).
    @JsonSerialize(using = ToStringSerializer.class)
    public java.sql.Date submittedOn;  // the reporting tool wants a plain date
    public Double averageRating;
    public int ratingCount;
    public List<Long> coSpeakerIds = new ArrayList<>();
}
