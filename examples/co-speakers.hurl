# Co-speakers: our own examples (the drip features have no published ones).
# Run: hurl --test --variable base_url=http://localhost:8080 co-speakers.hurl

POST {{base_url}}/speakers
{ "name": "Main Speaker", "email": "main-{{newUuid}}@example.com" }
HTTP 201
[Captures]
main_id: jsonpath "$.id"

POST {{base_url}}/speakers
{ "name": "Co One", "email": "co1-{{newUuid}}@example.com" }
HTTP 201
[Captures]
co1_id: jsonpath "$.id"

POST {{base_url}}/speakers
{ "name": "Co Two", "email": "co2-{{newUuid}}@example.com" }
HTTP 201
[Captures]
co2_id: jsonpath "$.id"

POST {{base_url}}/speakers
{ "name": "Co Three", "email": "co3-{{newUuid}}@example.com" }
HTTP 201
[Captures]
co3_id: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "Pairing on stage", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{main_id}} }
HTTP 201
[Captures]
talk_id: jsonpath "$.id"
[Asserts]
jsonpath "$.coSpeakerIds" count == 0

# Add two, in order
PUT {{base_url}}/talks/{{talk_id}}/co-speakers/{{co2_id}}
HTTP 200
[Asserts]
jsonpath "$.coSpeakerIds[0]" == {{co2_id}}

PUT {{base_url}}/talks/{{talk_id}}/co-speakers/{{co1_id}}
HTTP 200
[Asserts]
jsonpath "$.coSpeakerIds" count == 2
jsonpath "$.coSpeakerIds[0]" == {{co2_id}}
jsonpath "$.coSpeakerIds[1]" == {{co1_id}}

# Adding again changes nothing
PUT {{base_url}}/talks/{{talk_id}}/co-speakers/{{co1_id}}
HTTP 200
[Asserts]
jsonpath "$.coSpeakerIds" count == 2

# A third is refused, so is the main speaker
PUT {{base_url}}/talks/{{talk_id}}/co-speakers/{{co3_id}}
HTTP 409

PUT {{base_url}}/talks/{{talk_id}}/co-speakers/{{main_id}}
HTTP 409

# Shown on GET, but not in the co-speaker's own talk list
GET {{base_url}}/talks/{{talk_id}}
HTTP 200
[Asserts]
jsonpath "$.coSpeakerIds" count == 2

GET {{base_url}}/speakers/{{co1_id}}/talks
HTTP 200
[Asserts]
jsonpath "$" count == 0

# Remove
DELETE {{base_url}}/talks/{{talk_id}}/co-speakers/{{co2_id}}
HTTP 200
[Asserts]
jsonpath "$.coSpeakerIds" count == 1
jsonpath "$.coSpeakerIds[0]" == {{co1_id}}
