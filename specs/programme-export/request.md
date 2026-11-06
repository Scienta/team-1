# Programme export

Version: 1
Kind: drip feature (released at tick 133)
V: 1

## Context

As a conference organiser, I want the whole programme as JSON and as a CSV file, so that the website, the printed programme and the room signs can all be made from it.

## What we want

- Two read-only views of everything on the programme ([Programme scheduling](../scheduling/spec.md)): a JSON list for the website and a CSV file for the print shop. Both have one row per scheduled talk, nothing else, in the same order.
- The programme reads like a day: earliest start first, and within a slot by room (`R1`, `R2`, `R3`).
- Each row shows the slot, its start and end time (as in Programme scheduling), the room, and the talk's id, title, format and speakers as they are right now.
- Speakers are listed by name: the main speaker first, then the co-speakers in the order they were added ([Co-speakers](../co-speakers/spec.md)). In the CSV they're in one field, separated by a semicolon and a space (`Grace Hopper; Ada Lovelace`).
- The print shop's tooling is picky. The CSV is UTF-8, served as `text/csv` (even to clients that ask for JSON), and starts with exactly this header line:

  ```
  slot,start,end,room,talkId,title,format,speakers
  ```

  It's standard CSV ([RFC 4180](https://www.rfc-editor.org/rfc/rfc4180)) with Unix line endings, every line ending in a newline, and no blank line at the end. Fields are quoted only when they have to be (a comma, a quote or a line break in them), as in `"Kotlin, ""really"" fast"`, and are otherwise written as they are.
- An empty programme is an empty list, or a CSV with just the header.

## API

```yaml
paths:
  /programme:
    get:
      responses:
        '200':
          content:
            application/json:
              schema: { type: array, items: { $ref: '#/components/schemas/ProgrammeRow' } }
  /programme.csv:
    get:
      responses:
        '200':
          content:
            text/csv:
              schema: { type: string }
components:
  schemas:
    ProgrammeRow:
      type: object
      properties:
        slot: { type: string, enum: [ S1, S2, S3, S4, S5 ] }
        start: { type: string, example: '13:30' }
        end: { type: string, example: '15:30' }
        room: { type: string, enum: [ R1, R2, R3 ] }
        talkId: { type: integer }
        title: { type: string }
        format: { type: string, enum: [ LIGHTNING_10, LIGHTNING_20, PRESENTATION, WORKSHOP ] }
        speakers: { type: array, items: { type: string }, example: [ 'Grace Hopper', 'Ada Lovelace' ] }
```
