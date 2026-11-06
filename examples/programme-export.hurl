# Programme export (specs/programme-export/spec.md). Drip feature: our own light smoke; Vitest is the deep suite.
# NOT safe on a database whose programme matters (e.g. TEAM_URL while the harness runs): it DELETEs whatever is in
# R3/S5, places its own talk there, and leaves the cell empty at the end. Hurl can't capture a talkId from a cell
# that may be empty, so it can't put a previous occupant back. Run it locally or against a scratch database.
# Run: hurl --test --variable base_url=http://localhost:8080 programme-export.hurl

POST {{base_url}}/speakers
{ "name": "Grace Hopper", "email": "export-main-{{newUuid}}@example.com" }
HTTP 201
[Captures]
main_id: jsonpath "$.id"

POST {{base_url}}/speakers
{ "name": "Ada Lovelace", "email": "export-co-{{newUuid}}@example.com" }
HTTP 201
[Captures]
co_id: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "Kotlin, \"really\" fast", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{main_id}} }
HTTP 201
[Captures]
talk_id: jsonpath "$.id"

PUT {{base_url}}/talks/{{talk_id}}/co-speakers/{{co_id}}
HTTP 200

PUT {{base_url}}/talks/{{talk_id}}/ratings/1
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{talk_id}}/ratings/2
{ "score": 5 }
HTTP 200

PUT {{base_url}}/talks/{{talk_id}}/status
{ "status": "ACCEPTED" }
HTTP 200

# Free the cell (200 if something was there, 404 if empty)
DELETE {{base_url}}/programme/R3/S5
HTTP *
[Asserts]
status toString matches /^(200|404)$/

PUT {{base_url}}/programme/R3/S5
{ "talkId": {{talk_id}} }
HTTP 200

# R1, R4, R6, D18: JSON row with current title, format and speakers
GET {{base_url}}/programme
HTTP 200
[Asserts]
header "Content-Type" startsWith "application/json"
jsonpath "$" isCollection
jsonpath "$[?(@.talkId == {{talk_id}})].slot" == "S5"
jsonpath "$[?(@.talkId == {{talk_id}})].start" == "15:45"
jsonpath "$[?(@.talkId == {{talk_id}})].end" == "16:45"
jsonpath "$[?(@.talkId == {{talk_id}})].room" == "R3"
jsonpath "$[?(@.talkId == {{talk_id}})].title" == "Kotlin, \"really\" fast"
jsonpath "$[?(@.talkId == {{talk_id}})].format" == "PRESENTATION"
jsonpath "$[?(@.talkId == {{talk_id}})].speakers[0]" == "Grace Hopper"
jsonpath "$[?(@.talkId == {{talk_id}})].speakers[1]" == "Ada Lovelace"

# D15: trailing slash
GET {{base_url}}/programme/
HTTP 200
[Asserts]
jsonpath "$" isCollection

# D12: Accept text/csv on /programme is still JSON
GET {{base_url}}/programme
Accept: text/csv
HTTP 200
[Asserts]
header "Content-Type" startsWith "application/json"

# R7-R10: CSV header, quoting, "; " join, Unix line endings
GET {{base_url}}/programme.csv
HTTP 200
[Asserts]
header "Content-Type" startsWith "text/csv"
body startsWith "slot,start,end,room,talkId,title,format,speakers\n"
body contains "\nS5,15:45,16:45,R3,{{talk_id}},\"Kotlin, \"\"really\"\" fast\",PRESENTATION,Grace Hopper; Ada Lovelace\n"
body endsWith "\n"
body not endsWith "\n\n"
body not contains "\r\n"

# R7, D11: Accept application/json still gets CSV, never 406
GET {{base_url}}/programme.csv
Accept: application/json
HTTP 200
[Asserts]
header "Content-Type" startsWith "text/csv"
body startsWith "slot,start,end,room,talkId,title,format,speakers\n"

# D17: HEAD is the GET's headers without a body
HEAD {{base_url}}/programme.csv
HTTP 200
[Asserts]
header "Content-Type" startsWith "text/csv"
bytes count == 0

# D16: read-only
POST {{base_url}}/programme
{}
HTTP 405

DELETE {{base_url}}/programme.csv
HTTP 405

# D1: the cell path is unchanged
GET {{base_url}}/programme/R3/S5
HTTP 200
[Asserts]
jsonpath "$.talkId" == {{talk_id}}

# Clean up
DELETE {{base_url}}/programme/R3/S5
HTTP 204
