# Acceptance state machine: our own examples (specs/acceptance/spec.md; drip features have no published ones).
# Run: hurl --test --variable base_url=http://localhost:8080 acceptance.hurl

POST {{base_url}}/speakers
{ "name": "Main Speaker", "email": "acc-main-{{newUuid}}@example.com" }
HTTP 201
[Captures]
main_id: jsonpath "$.id"

POST {{base_url}}/speakers
{ "name": "Co Speaker", "email": "acc-co-{{newUuid}}@example.com" }
HTTP 201
[Captures]
co_id: jsonpath "$.id"

POST {{base_url}}/speakers
{ "name": "Stranger", "email": "acc-stranger-{{newUuid}}@example.com" }
HTTP 201
[Captures]
stranger_id: jsonpath "$.id"

# Talk A: waitlisted, then accepted, then withdrawn
POST {{base_url}}/talks
{ "title": "Talk A", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{main_id}} }
HTTP 201
[Captures]
a_id: jsonpath "$.id"
[Asserts]
jsonpath "$.status" == "SUBMITTED"

PUT {{base_url}}/talks/{{a_id}}/co-speakers/{{co_id}}
HTTP 200

# D1: unknown talk
PUT {{base_url}}/talks/999999999/status
{ "status": "REJECTED" }
HTTP 404

POST {{base_url}}/talks/999999999/withdraw
{ "speakerId": {{main_id}} }
HTTP 404

# D2: not one of the three decisions
PUT {{base_url}}/talks/{{a_id}}/status
{ "status": "accepted" }
HTTP 400

PUT {{base_url}}/talks/{{a_id}}/status
{ "status": "SUBMITTED" }
HTTP 400

PUT {{base_url}}/talks/{{a_id}}/status
{ "status": "WITHDRAWN" }
HTTP 400

PUT {{base_url}}/talks/{{a_id}}/status
{}
HTTP 400

PUT {{base_url}}/talks/{{a_id}}/status
{ "status": 1 }
HTTP 400

# D3: not JSON
PUT {{base_url}}/talks/{{a_id}}/status
Content-Type: application/json
```
not json
```
HTTP 400

# R3, D5: no ratings, one reviewer, the same reviewer twice: 409
PUT {{base_url}}/talks/{{a_id}}/status
{ "status": "ACCEPTED" }
HTTP 409

PUT {{base_url}}/talks/{{a_id}}/ratings/1
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{a_id}}/ratings/1
{ "score": 5 }
HTTP 200

PUT {{base_url}}/talks/{{a_id}}/status
{ "status": "ACCEPTED" }
HTTP 409

GET {{base_url}}/talks/{{a_id}}
HTTP 200
[Asserts]
jsonpath "$.status" == "SUBMITTED"

# R2: waitlist, D6: waitlist again is 409
PUT {{base_url}}/talks/{{a_id}}/status
{ "status": "WAITLISTED" }
HTTP 200
[Asserts]
jsonpath "$.status" == "WAITLISTED"

PUT {{base_url}}/talks/{{a_id}}/status
{ "status": "WAITLISTED" }
HTTP 409

GET {{base_url}}/talks/{{a_id}}
HTTP 200
[Asserts]
jsonpath "$.status" == "WAITLISTED"

# R7: shows in lists
GET {{base_url}}/speakers/{{main_id}}/talks
HTTP 200
[Asserts]
jsonpath "$[0].id" == {{a_id}}
jsonpath "$[0].status" == "WAITLISTED"

GET {{base_url}}/talks?speakerId={{main_id}}
HTTP 200
[Asserts]
jsonpath "$[0].id" == {{a_id}}
jsonpath "$[0].status" == "WAITLISTED"

# Second reviewer: now it can be accepted
PUT {{base_url}}/talks/{{a_id}}/ratings/2
{ "score": 3 }
HTTP 200

PUT {{base_url}}/talks/{{a_id}}/status
{ "status": "ACCEPTED" }
HTTP 200
[Asserts]
jsonpath "$.id" == {{a_id}}
jsonpath "$.status" == "ACCEPTED"

# D7: the decision is final: accepting again is 409, so is a different decision
PUT {{base_url}}/talks/{{a_id}}/status
{ "status": "ACCEPTED" }
HTTP 409

PUT {{base_url}}/talks/{{a_id}}/status
{ "status": "REJECTED" }
HTTP 409

PUT {{base_url}}/talks/{{a_id}}/status
{ "status": "WAITLISTED" }
HTTP 409

# D8: speakerId must be a whole number
POST {{base_url}}/talks/{{a_id}}/withdraw
{}
HTTP 400

POST {{base_url}}/talks/{{a_id}}/withdraw
{ "speakerId": "{{main_id}}" }
HTTP 400

# D9: a co-speaker or another speaker can't withdraw; D10: nor an unknown id
POST {{base_url}}/talks/{{a_id}}/withdraw
{ "speakerId": {{co_id}} }
HTTP 403

POST {{base_url}}/talks/{{a_id}}/withdraw
{ "speakerId": {{stranger_id}} }
HTTP 403

POST {{base_url}}/talks/{{a_id}}/withdraw
{ "speakerId": 999999999 }
HTTP 403

GET {{base_url}}/talks/{{a_id}}
HTTP 200
[Asserts]
jsonpath "$.status" == "ACCEPTED"

# R4: the main speaker withdraws an accepted talk
POST {{base_url}}/talks/{{a_id}}/withdraw
{ "speakerId": {{main_id}} }
HTTP 200
[Asserts]
jsonpath "$.id" == {{a_id}}
jsonpath "$.status" == "WITHDRAWN"

# R6, D11: withdrawn is final
POST {{base_url}}/talks/{{a_id}}/withdraw
{ "speakerId": {{main_id}} }
HTTP 409

PUT {{base_url}}/talks/{{a_id}}/status
{ "status": "ACCEPTED" }
HTTP 409

PUT {{base_url}}/talks/{{a_id}}/status
{ "status": "WAITLISTED" }
HTTP 409

PUT {{base_url}}/talks/{{a_id}}/status
{ "status": "REJECTED" }
HTTP 409

# D12: a stranger gets 403 whatever the state
POST {{base_url}}/talks/{{a_id}}/withdraw
{ "speakerId": {{stranger_id}} }
HTTP 403

GET {{base_url}}/talks/{{a_id}}
HTTP 200
[Asserts]
jsonpath "$.status" == "WITHDRAWN"

# Talk B: rejected (no ratings needed), final, can't be withdrawn
POST {{base_url}}/talks
{ "title": "Talk B", "format": "LIGHTNING_10", "level": "ADVANCED", "speakerId": {{main_id}} }
HTTP 201
[Captures]
b_id: jsonpath "$.id"

PUT {{base_url}}/talks/{{b_id}}/status
{ "status": "REJECTED" }
HTTP 200
[Asserts]
jsonpath "$.status" == "REJECTED"

# D7: rejecting again is 409 too
PUT {{base_url}}/talks/{{b_id}}/status
{ "status": "REJECTED" }
HTTP 409

PUT {{base_url}}/talks/{{b_id}}/status
{ "status": "ACCEPTED" }
HTTP 409

PUT {{base_url}}/talks/{{b_id}}/status
{ "status": "WAITLISTED" }
HTTP 409

POST {{base_url}}/talks/{{b_id}}/withdraw
{ "speakerId": {{main_id}} }
HTTP 409

# Talk C: withdrawn straight from SUBMITTED
POST {{base_url}}/talks
{ "title": "Talk C", "format": "WORKSHOP", "level": "INTERMEDIATE", "speakerId": {{main_id}} }
HTTP 201
[Captures]
c_id: jsonpath "$.id"

POST {{base_url}}/talks/{{c_id}}/withdraw
{ "speakerId": {{main_id}} }
HTTP 200
[Asserts]
jsonpath "$.status" == "WITHDRAWN"

# Talk E (the co-speaker's own): withdrawn from WAITLISTED
POST {{base_url}}/talks
{ "title": "Talk E", "format": "LIGHTNING_20", "level": "BEGINNER", "speakerId": {{co_id}} }
HTTP 201
[Captures]
e_id: jsonpath "$.id"

PUT {{base_url}}/talks/{{e_id}}/status
{ "status": "WAITLISTED" }
HTTP 200
[Asserts]
jsonpath "$.status" == "WAITLISTED"

# D3: withdraw with no body
POST {{base_url}}/talks/{{e_id}}/withdraw
HTTP 400

POST {{base_url}}/talks/{{e_id}}/withdraw
{ "speakerId": {{co_id}} }
HTTP 200
[Asserts]
jsonpath "$.status" == "WITHDRAWN"

# D13: withdrawn and rejected talks still count: a fourth talk is refused
POST {{base_url}}/talks
{ "title": "Talk D", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{main_id}} }
HTTP 409
