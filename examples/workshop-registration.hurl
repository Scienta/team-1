# Workshop registration with capacity: our own examples (specs/workshop-registration/spec.md; drip features have
# no published ones). Every rejection in the spec's decision table, then the happy path up to the 21st seat.
# Run: hurl --test --variable base_url=http://localhost:8080 workshop-registration.hurl

POST {{base_url}}/speakers
{ "name": "Workshop Host", "email": "ws-host-{{newUuid}}@example.com" }
HTTP 201
[Captures]
host_id: jsonpath "$.id"
host_email: jsonpath "$.email"

POST {{base_url}}/speakers
{ "name": "Other Host", "email": "ws-other-{{newUuid}}@example.com" }
HTTP 201
[Captures]
other_id: jsonpath "$.id"

# The workshop: two reviewers, then accepted
POST {{base_url}}/talks
{ "title": "Hands-on Postgres", "format": "WORKSHOP", "level": "BEGINNER", "speakerId": {{host_id}} }
HTTP 201
[Captures]
ws_id: jsonpath "$.id"

PUT {{base_url}}/talks/{{ws_id}}/ratings/1
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{ws_id}}/ratings/2
{ "score": 5 }
HTTP 200

PUT {{base_url}}/talks/{{ws_id}}/status
{ "status": "ACCEPTED" }
HTTP 200
[Asserts]
jsonpath "$.status" == "ACCEPTED"

# A presentation, accepted too: not a workshop
POST {{base_url}}/talks
{ "title": "A presentation", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{host_id}} }
HTTP 201
[Captures]
pres_id: jsonpath "$.id"

PUT {{base_url}}/talks/{{pres_id}}/ratings/1
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{pres_id}}/ratings/2
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{pres_id}}/status
{ "status": "ACCEPTED" }
HTTP 200

# A workshop that is still SUBMITTED
POST {{base_url}}/talks
{ "title": "Not decided yet", "format": "WORKSHOP", "level": "ADVANCED", "speakerId": {{host_id}} }
HTTP 201
[Captures]
sub_id: jsonpath "$.id"

# D15: listing is always allowed, [] when nobody registered
GET {{base_url}}/workshops/{{ws_id}}/registrations
HTTP 200
[Asserts]
jsonpath "$" count == 0

GET {{base_url}}/workshops/{{sub_id}}/registrations
HTTP 200
[Asserts]
jsonpath "$" count == 0

# R1, R8, D13: 201, name and email as typed
POST {{base_url}}/workshops/{{ws_id}}/registrations
{ "name": "Ada Lovelace", "email": "ADA-{{newUuid}}@Example.com" }
HTTP 201
[Captures]
ada_id: jsonpath "$.id"
ada_email: jsonpath "$.email"
ada_other_case: jsonpath "$.email" replace "ADA-" "ada-" replace "@Example.com" "@EXAMPLE.COM"
[Asserts]
jsonpath "$.id" isInteger
jsonpath "$.workshopId" == {{ws_id}}
jsonpath "$.name" == "Ada Lovelace"
jsonpath "$.email" startsWith "ADA-"
jsonpath "$.email" endsWith "@Example.com"
jsonpath "$.email" replace "ADA-" "ada-" replace "@Example.com" "@EXAMPLE.COM" startsWith "ada-"
jsonpath "$.email" replace "ADA-" "ada-" replace "@Example.com" "@EXAMPLE.COM" endsWith "@EXAMPLE.COM"

# D1: unknown talk
POST {{base_url}}/workshops/999999999/registrations
{ "name": "Ada", "email": "ada@example.com" }
HTTP 404

GET {{base_url}}/workshops/999999999/registrations
HTTP 404

# R2, D2: a talk that isn't a workshop is no workshop
POST {{base_url}}/workshops/{{pres_id}}/registrations
{ "name": "Ada", "email": "ada@example.com" }
HTTP 404

GET {{base_url}}/workshops/{{pres_id}}/registrations
HTTP 404

# D3: the id isn't an integer
POST {{base_url}}/workshops/abc/registrations
{ "name": "Ada", "email": "ada@example.com" }
HTTP 400

GET {{base_url}}/workshops/abc/registrations
HTTP 400

# D4: no body, not JSON, not an object
POST {{base_url}}/workshops/{{ws_id}}/registrations
Content-Type: application/json
HTTP 400

POST {{base_url}}/workshops/{{ws_id}}/registrations
Content-Type: application/json
```
not json
```
HTTP 400

POST {{base_url}}/workshops/{{ws_id}}/registrations
[ "Ada" ]
HTTP 400

# D5: name missing, null, blank or not a string
POST {{base_url}}/workshops/{{ws_id}}/registrations
{ "email": "ws-{{newUuid}}@example.com" }
HTTP 400

POST {{base_url}}/workshops/{{ws_id}}/registrations
{ "name": null, "email": "ws-{{newUuid}}@example.com" }
HTTP 400

POST {{base_url}}/workshops/{{ws_id}}/registrations
{ "name": "   ", "email": "ws-{{newUuid}}@example.com" }
HTTP 400

POST {{base_url}}/workshops/{{ws_id}}/registrations
{ "name": 42, "email": "ws-{{newUuid}}@example.com" }
HTTP 400

# D6: email missing, null, blank or not a string
POST {{base_url}}/workshops/{{ws_id}}/registrations
{ "name": "Ada" }
HTTP 400

POST {{base_url}}/workshops/{{ws_id}}/registrations
{ "name": "Ada", "email": null }
HTTP 400

POST {{base_url}}/workshops/{{ws_id}}/registrations
{ "name": "Ada", "email": "" }
HTTP 400

POST {{base_url}}/workshops/{{ws_id}}/registrations
{ "name": "Ada", "email": 42 }
HTTP 400

# D7: not an email
POST {{base_url}}/workshops/{{ws_id}}/registrations
{ "name": "Ada", "email": "ada" }
HTTP 400

POST {{base_url}}/workshops/{{ws_id}}/registrations
{ "name": "Ada", "email": "ada@example" }
HTTP 400

# R3, D8: a workshop that isn't accepted is 409
POST {{base_url}}/workshops/{{sub_id}}/registrations
{ "name": "Ada", "email": "ws-{{newUuid}}@example.com" }
HTTP 409

# D18: a bad body beats the 409
POST {{base_url}}/workshops/{{sub_id}}/registrations
{ "name": "Ada" }
HTTP 400

# D18: an unknown talk or a non-workshop beats a bad body
POST {{base_url}}/workshops/999999999/registrations
Content-Type: application/json
```
not json
```
HTTP 404

POST {{base_url}}/workshops/{{pres_id}}/registrations
{}
HTTP 404

# R6, D9, D12: the same email again, in another case, or with whitespace around it, is 409
POST {{base_url}}/workshops/{{ws_id}}/registrations
{ "name": "Ada Again", "email": "{{ada_email}}" }
HTTP 409

POST {{base_url}}/workshops/{{ws_id}}/registrations
{ "name": "Ada Again", "email": "{{ada_other_case}}" }
HTTP 409

POST {{base_url}}/workshops/{{ws_id}}/registrations
{ "name": "Ada Again", "email": "  {{ada_email}}  " }
HTTP 409

POST {{base_url}}/workshops/{{ws_id}}/registrations
{ "name": "Ada Again", "email": "\t{{ada_email}}\n" }
HTTP 409

# D14: unknown fields are ignored. D17: the workshop's own speaker may register.
POST {{base_url}}/workshops/{{ws_id}}/registrations
{ "name": "Workshop Host", "email": "{{host_email}}", "phone": "12345678" }
HTTP 201
[Captures]
host_reg_id: jsonpath "$.id"
[Asserts]
jsonpath "$.phone" not exists

# R9, D9: two registrations, in order, and the first kept its name
GET {{base_url}}/workshops/{{ws_id}}/registrations
HTTP 200
[Asserts]
jsonpath "$" count == 2
jsonpath "$[0].id" == {{ada_id}}
jsonpath "$[0].name" == "Ada Lovelace"
jsonpath "$[0].email" == "{{ada_email}}"
jsonpath "$[1].id" == {{host_reg_id}}

# R7: the same person can register for another workshop
POST {{base_url}}/talks
{ "title": "Second workshop", "format": "WORKSHOP", "level": "BEGINNER", "speakerId": {{other_id}} }
HTTP 201
[Captures]
ws2_id: jsonpath "$.id"

PUT {{base_url}}/talks/{{ws2_id}}/ratings/1
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{ws2_id}}/ratings/2
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{ws2_id}}/status
{ "status": "ACCEPTED" }
HTTP 200

POST {{base_url}}/workshops/{{ws2_id}}/registrations
{ "name": "Ada Lovelace", "email": "{{ada_email}}" }
HTTP 201
[Asserts]
jsonpath "$.workshopId" == {{ws2_id}}

# R5: 18 more fill the workshop to 20
POST {{base_url}}/workshops/{{ws_id}}/registrations
[Options]
repeat: 18
{ "name": "Attendee", "email": "ws-{{newUuid}}@example.com" }
HTTP 201

GET {{base_url}}/workshops/{{ws_id}}/registrations
HTTP 200
[Asserts]
jsonpath "$" count == 20
jsonpath "$[0].id" == {{ada_id}}

# R5, D10: the 21st is 409, and nothing is stored
POST {{base_url}}/workshops/{{ws_id}}/registrations
{ "name": "Number 21", "email": "ws-{{newUuid}}@example.com" }
HTTP 409

# D11: a duplicate on a full workshop is 409 too
POST {{base_url}}/workshops/{{ws_id}}/registrations
{ "name": "Ada Again", "email": "{{ada_email}}" }
HTTP 409

GET {{base_url}}/workshops/{{ws_id}}/registrations
HTTP 200
[Asserts]
jsonpath "$" count == 20

# D19: no cancelling
DELETE {{base_url}}/workshops/{{ws_id}}/registrations
HTTP 405

# R4, D8, D15, D20: withdrawn, the list stays and new registrations are 409
POST {{base_url}}/talks/{{ws2_id}}/withdraw
{ "speakerId": {{other_id}} }
HTTP 200
[Asserts]
jsonpath "$.status" == "WITHDRAWN"

GET {{base_url}}/workshops/{{ws2_id}}/registrations
HTTP 200
[Asserts]
jsonpath "$" count == 1
jsonpath "$[0].name" == "Ada Lovelace"

POST {{base_url}}/workshops/{{ws2_id}}/registrations
{ "name": "Late", "email": "ws-{{newUuid}}@example.com" }
HTTP 409

POST {{base_url}}/talks/{{ws_id}}/withdraw
{ "speakerId": {{host_id}} }
HTTP 200

GET {{base_url}}/workshops/{{ws_id}}/registrations
HTTP 200
[Asserts]
jsonpath "$" count == 20

POST {{base_url}}/workshops/{{ws_id}}/registrations
{ "name": "Late", "email": "ws-{{newUuid}}@example.com" }
HTTP 409
