# Spring Boot 4 upgrade (specs/spring-boot-4/spec.md): the running versions, and the invariants a client sees.
# Run: hurl --test --variable base_url=http://localhost:8080 spring-boot-4.hurl

# R1-R3: the real running versions
GET {{base_url}}/actuator/info
HTTP 200
[Asserts]
jsonpath "$.versions.springBoot" startsWith "4."
jsonpath "$.versions.springFramework" startsWith "7."
jsonpath "$.versions.java" startsWith "25"

# R13: the deploy contract's health body
GET {{base_url}}/health
HTTP 200
[Asserts]
jsonpath "$.status" == "ok"
jsonpath "$.version" exists

# R11, D7, R7: a speaker via the legacy trailing-slash URL, with a JSON comment, no bio
POST {{base_url}}/speakers/
Content-Type: application/json
```
{ /* legacy client */ "name": "Boot Four", "email": "boot4-{{newUuid}}@example.com" }
```
HTTP 201
[Captures]
speaker_id: jsonpath "$.id"
[Asserts]
jsonpath "$.bio" == null

GET {{base_url}}/speakers/{{speaker_id}}/
HTTP 200
[Asserts]
jsonpath "$.id" == {{speaker_id}}

# R4, R5, R7, R10: formats, nulls and an ignored unknown field
POST {{base_url}}/talks
{
  "title": "Upgrading quietly",
  "format": "LIGHTNING_20",
  "level": "ADVANCED",
  "speakerId": {{speaker_id}},
  "durationMinutes": 999
}
HTTP 201
[Captures]
talk_id: jsonpath "$.id"
[Asserts]
jsonpath "$.durationMinutes" == 20
jsonpath "$.submittedAt" matches /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}\+00:00$/
jsonpath "$.submittedOn" matches /^\d{4}-\d\d-\d\d$/
jsonpath "$.averageRating" == null

# R11 (review #2): trailing slashes work on the talk and rating URLs too; R6: the display time
PUT {{base_url}}/talks/{{talk_id}}/ratings/1/
{ "score": 4 }
HTTP 200
[Asserts]
jsonpath "$.ratedAtDisplay" matches /^\d{1,2}\/\d{1,2}\/\d\d, \d{1,2}:\d\d (AM|PM)$/

GET {{base_url}}/talks/{{talk_id}}/
HTTP 200
[Asserts]
jsonpath "$.averageRating" == 4.0

# R9, R12: framework-produced 400s keep Boot's error shape, with the message
PUT {{base_url}}/talks/{{talk_id}}/ratings/abc
{ "score": 3 }
HTTP 400
[Asserts]
header "Content-Type" == "application/json;charset=UTF-8"
jsonpath "$.timestamp" matches /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}\+00:00$/
jsonpath "$.status" == 400
jsonpath "$.error" == "Bad Request"
jsonpath "$.message" isString
jsonpath "$.path" == "/talks/{{talk_id}}/ratings/abc"

POST {{base_url}}/talks
Content-Type: application/json
```
{not json
```
HTTP 400
[Asserts]
jsonpath "$.status" == 400
jsonpath "$.message" isString
