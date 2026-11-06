# Acceptance state machine

Version: 1
Kind: drip feature (released at tick 20)
V: 2

## Context

As the programme committee, I want to accept, reject or waitlist submitted talks, and as a speaker I want to withdraw my talk, so that the programme is built only from talks that are reviewed and still on offer.

Every talk starts as `SUBMITTED` ([Talk submissions](../talks/spec.md)), and today nothing can change that.

## What we want

- The committee decides on a talk: accept it, reject it or put it on the waitlist.
- A waitlisted talk can still be accepted or rejected later. Once a talk is accepted or rejected, the committee's decision is final.
- We only accept a talk that at least two different reviewers have rated ([Ratings](../ratings/spec.md)). A reviewer who rated it twice is still one reviewer. Rejecting and waitlisting need no ratings.
- A speaker can withdraw their own talk, unless it was already rejected or withdrawn. Nobody else can withdraw it. A withdrawn talk is out for good.
- A talk's current status shows wherever the talk is shown.
- A decision or withdrawal that isn't allowed changes nothing. The committee is several people clicking at once, so two of them deciding on the same talk must never leave it in a state the rules don't allow.

## API

```yaml
paths:
  /talks/{id}/status:
    put:
      parameters: [ { name: id, in: path, required: true, schema: { type: integer } } ]
      requestBody:
        content:
          application/json:
            schema: { $ref: '#/components/schemas/Decision' }
      responses:
        '200': { content: { application/json: { schema: { $ref: '../talks/spec.md#/components/schemas/Talk' } } } }
  /talks/{id}/withdraw:
    post:
      parameters: [ { name: id, in: path, required: true, schema: { type: integer } } ]
      requestBody:
        content:
          application/json:
            schema: { $ref: '#/components/schemas/Withdrawal' }
      responses:
        '200': { content: { application/json: { schema: { $ref: '../talks/spec.md#/components/schemas/Talk' } } } }
components:
  schemas:
    Status: { type: string, enum: [ SUBMITTED, ACCEPTED, REJECTED, WAITLISTED, WITHDRAWN ] }
    Decision:
      type: object
      properties:
        status: { type: string, enum: [ ACCEPTED, REJECTED, WAITLISTED ] }
    Withdrawal:
      type: object
      properties:
        speakerId: { type: integer, description: the speaker asking to withdraw }
    # Talk.status (Talk submissions spec) now takes any value of Status.
```
