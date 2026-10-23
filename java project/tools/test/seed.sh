#!/usr/bin/env bash
# Seeds the baseline test data used by cdp-test.mjs against a running instance.
#
# The API is session- and CSRF-protected, so this script signs in as the
# administrator, keeps the session cookie and sends X-XSRF-TOKEN on every
# mutating request.
#
# Usage:  bash tools/test/seed.sh [http://localhost:8081/api]
set -euo pipefail

B=${1:-http://localhost:8081/api}
TMP=$(mktemp -d)
COOKIES="$TMP/cookies.txt"
trap 'rm -rf "$TMP"' EXIT

TOKEN=""

csrf_token() {
  curl -s -b "$COOKIES" -c "$COOKIES" "$B/auth/csrf" \
    | python -c "import sys,json; print(json.load(sys.stdin).get('token',''))"
}

login() {
  curl -s -b "$COOKIES" -c "$COOKIES" -X POST "$B/auth/login" \
    -H 'Content-Type: application/json' \
    -d '{"username":"admin","password":"admin"}' -o /dev/null
  TOKEN=$(csrf_token)
  if [ -z "$TOKEN" ]; then
    echo "seed: administrator login or CSRF token failed (is the app running at $B?)" >&2
    exit 1
  fi
}

# api METHOD PATH [JSON_BODY]
api() {
  local method=$1 path=$2 body=${3:-}
  if [ -n "$body" ]; then
    curl -s -b "$COOKIES" -c "$COOKIES" -X "$method" "$B$path" \
      -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $TOKEN" -d "$body"
  else
    curl -s -b "$COOKIES" -c "$COOKIES" -X "$method" "$B$path" \
      -H "X-XSRF-TOKEN: $TOKEN"
  fi
}

delete_all() {
  local resource=$1
  local ids id status
  ids=$(api GET "/$resource" | python -c "
import sys, json
try:
    print(' '.join(str(x['id']) for x in json.load(sys.stdin)))
except Exception:
    print('')
")
  for id in $ids; do
    # A published exam must be cancelled before it can be deleted.
    status=$(api DELETE "/$resource/$id" | python -c "
import sys, json
try:
    print(json.load(sys.stdin).get('deleted', ''))
except Exception:
    print('')
")
    if [ "$resource" = "exams" ] && [ "$status" != "True" ]; then
      api POST "/exams/$id/status" '{"status":"CANCELLED"}' >/dev/null
      api DELETE "/exams/$id" >/dev/null
    fi
  done
}

login
echo "seed: signed in as administrator"

# --- reset existing data so the baseline is identical on every run ---
delete_all exams
delete_all rooms
delete_all students

for s in '101|Ananya Sharma|CSE|A' '102|Rahul|ECE|B' '103|Priya Sharma|CSE|A' '104|Karthik|MECH|C'; do
  IFS='|' read -r r n b m <<< "$s"
  api POST /students "{\"rollNo\":\"$r\",\"name\":\"$n\",\"branch\":\"$b\",\"batch\":\"$m\"}" -o /dev/null
done

api POST /rooms '{"roomNumber":"121","capacity":20,"rowsCount":5,"columnsCount":3}' -o /dev/null
api POST /rooms '{"roomNumber":"122","capacity":30,"rowsCount":6,"columnsCount":5}' -o /dev/null

EXAM=$(api POST /exams '{"subjectName":"Data Structures","branch":"","course":"","examDate":"2026-11-02","examTime":"10:00","applicationId":"APP-20261102-001"}')
EXAM_ID=$(printf '%s' "$EXAM" | python -c "import sys,json; print(json.load(sys.stdin).get('id',''))")
if [ -z "$EXAM_ID" ]; then
  echo "seed: exam creation failed: $EXAM" >&2
  exit 1
fi
api POST /allocate "{\"examId\":$EXAM_ID}" -o /dev/null

# baseline import files (CSV + XLSX + PDF fixtures)
cd "$(dirname "$0")" || exit 1
for fixture in test-students.csv test-students.xlsx test-students.pdf; do
  curl -s -b "$COOKIES" -c "$COOKIES" -X POST "$B/import/students" \
    -H "X-XSRF-TOKEN: $TOKEN" -F "file=@$fixture" -o /dev/null
done

COUNT=$(api GET /students | python -c "import sys,json; print(len(json.load(sys.stdin)))")
echo "seeded: $COUNT students (exam $EXAM_ID)"
