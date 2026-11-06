# Programme scheduling (specs/scheduling/spec.md). Drip feature — our own examples.
# Run: hurl --test --variable base_url=http://localhost:8080 scheduling.hurl

POST {{base_url}}/speakers
{ "name": "Sched Main", "email": "sched-main-{{newUuid}}@example.com" }
HTTP 201
[Captures]
main_id: jsonpath "$.id"

POST {{base_url}}/speakers
{ "name": "Sched Other", "email": "sched-other-{{newUuid}}@example.com" }
HTTP 201
[Captures]
other_id: jsonpath "$.id"

POST {{base_url}}/speakers
{ "name": "Sched Co", "email": "sched-co-{{newUuid}}@example.com" }
HTTP 201
[Captures]
co_id: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "Talk A", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{main_id}} }
HTTP 201
[Captures]
a_id: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "Talk B", "format": "LIGHTNING_20", "level": "BEGINNER", "speakerId": {{other_id}} }
HTTP 201
[Captures]
b_id: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "Workshop", "format": "WORKSHOP", "level": "BEGINNER", "speakerId": {{main_id}} }
HTTP 201
[Captures]
ws_id: jsonpath "$.id"

# Accept A, B and the workshop (need two reviewers each)
PUT {{base_url}}/talks/{{a_id}}/ratings/1
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{a_id}}/ratings/2
{ "score": 5 }
HTTP 200

PUT {{base_url}}/talks/{{a_id}}/status
{ "status": "ACCEPTED" }
HTTP 200

PUT {{base_url}}/talks/{{b_id}}/ratings/1
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{b_id}}/ratings/2
{ "score": 5 }
HTTP 200

PUT {{base_url}}/talks/{{b_id}}/status
{ "status": "ACCEPTED" }
HTTP 200

PUT {{base_url}}/talks/{{ws_id}}/ratings/1
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{ws_id}}/ratings/2
{ "score": 5 }
HTTP 200

PUT {{base_url}}/talks/{{ws_id}}/status
{ "status": "ACCEPTED" }
HTTP 200

# D1: unknown / wrong-case room / slot
PUT {{base_url}}/programme/R9/S1
{ "talkId": {{a_id}} }
HTTP 404

PUT {{base_url}}/programme/R1/S6
{ "talkId": {{a_id}} }
HTTP 404

PUT {{base_url}}/programme/r1/S1
{ "talkId": {{a_id}} }
HTTP 404

PUT {{base_url}}/programme/R1/s1
{ "talkId": {{a_id}} }
HTTP 404

# D3: bad body
PUT {{base_url}}/programme/R1/S1
{}
HTTP 400

PUT {{base_url}}/programme/R1/S1
{ "talkId": null }
HTTP 400

# D4: unknown talk is 400 (harness check-1 failed on 404)
PUT {{base_url}}/programme/R1/S1
{ "talkId": 999999999 }
HTTP 400

# D5: non-ACCEPTED
POST {{base_url}}/talks
{ "title": "Submitted only", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{other_id}} }
HTTP 201
[Captures]
submitted_id: jsonpath "$.id"

PUT {{base_url}}/programme/R1/S1
{ "talkId": {{submitted_id}} }
HTTP 409

# D6: workshop in short slot
PUT {{base_url}}/programme/R1/S1
{ "talkId": {{ws_id}} }
HTTP 409

# Happy path: place A in R1/S1
PUT {{base_url}}/programme/R1/S1
{ "talkId": {{a_id}} }
HTTP 200
[Asserts]
jsonpath "$.room" == "R1"
jsonpath "$.slot" == "S1"
jsonpath "$.start" == "09:00"
jsonpath "$.end" == "10:00"
jsonpath "$.talkId" == {{a_id}}

# D9: same talk same cell again
PUT {{base_url}}/programme/R1/S1
{ "talkId": {{a_id}} }
HTTP 200
[Asserts]
jsonpath "$.talkId" == {{a_id}}

# GET occupied / empty
GET {{base_url}}/programme/R1/S1
HTTP 200
[Asserts]
jsonpath "$.talkId" == {{a_id}}

GET {{base_url}}/programme/R2/S1
HTTP 404

# D7: cell occupied by another talk
PUT {{base_url}}/programme/R1/S1
{ "talkId": {{b_id}} }
HTTP 409

# D8: talk already elsewhere
PUT {{base_url}}/programme/R2/S2
{ "talkId": {{a_id}} }
HTTP 409

# R6: B can share the slot in another room (different speakers)
PUT {{base_url}}/programme/R2/S1
{ "talkId": {{b_id}} }
HTTP 200
[Asserts]
jsonpath "$.room" == "R2"
jsonpath "$.slot" == "S1"
jsonpath "$.talkId" == {{b_id}}

# Workshop fits S4
PUT {{base_url}}/programme/R3/S4
{ "talkId": {{ws_id}} }
HTTP 200
[Asserts]
jsonpath "$.start" == "13:30"
jsonpath "$.end" == "15:30"

# Co-speaker clash: add co to B, try to place a talk with that co as main in same slot
# First free R2/S1 so we can re-use the slot with a conflicting talk after scheduling A alone.
DELETE {{base_url}}/programme/R2/S1
HTTP 204

PUT {{base_url}}/talks/{{a_id}}/co-speakers/{{co_id}}
HTTP 200

POST {{base_url}}/talks
{ "title": "Talk by co", "format": "LIGHTNING_10", "level": "BEGINNER", "speakerId": {{co_id}} }
HTTP 201
[Captures]
co_talk_id: jsonpath "$.id"

PUT {{base_url}}/talks/{{co_talk_id}}/ratings/1
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{co_talk_id}}/ratings/2
{ "score": 5 }
HTTP 200

PUT {{base_url}}/talks/{{co_talk_id}}/status
{ "status": "ACCEPTED" }
HTTP 200

# A is in R1/S1 with co as co-speaker; co's own talk into R2/S1 must 409
PUT {{base_url}}/programme/R2/S1
{ "talkId": {{co_talk_id}} }
HTTP 409

# D11 / D12: delete is 204 with no body (harness checks 2-6 failed on 200 + Entry); empty delete is 404
DELETE {{base_url}}/programme/R1/S1
HTTP 204
[Asserts]
bytes count == 0

GET {{base_url}}/programme/R1/S1
HTTP 404

DELETE {{base_url}}/programme/R1/S1
HTTP 404

# Removed talk can be placed elsewhere
PUT {{base_url}}/programme/R3/S5
{ "talkId": {{a_id}} }
HTTP 200
[Asserts]
jsonpath "$.start" == "15:45"
jsonpath "$.end" == "16:45"
