#!/usr/bin/env python
"""End-to-end smoke test of the exam endpoints against a running server.

Usage:  python tools/test/exam-api-smoke.py [http://localhost:8080]

Signs in as the administrator, then exercises the exam CRUD + lifecycle rules
that the unit tests cover, this time through real HTTP and the real database.
Exits non-zero if any check fails.
"""
import json
import sys
import urllib.error
import urllib.request
import http.cookiejar

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080"
jar = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
failures = []
token = None


def call(method, path, payload=None, csrf=True, retry=True):
    global token
    body = json.dumps(payload).encode() if payload is not None else None
    request = urllib.request.Request(BASE + path, data=body, method=method)
    if body:
        request.add_header("Content-Type", "application/json")
    if csrf and token:
        request.add_header("X-XSRF-TOKEN", str(token))
    try:
        with opener.open(request) as response:
            return response.status, response.read().decode()
    except urllib.error.HTTPError as error:
        status = error.code
        text = error.read().decode()
        # The session (and with it the CSRF token) changes when signing in, so a
        # mutation that is refused is retried once with a freshly loaded token.
        if status == 403 and retry and (body is not None):
            load_csrf_token()
            return call(method, path, payload, csrf, retry=False)
        return status, text


def load_csrf_token():
    """Loads the CSRF token for the current session (also refreshes the cookie)."""
    global token
    status, text = call("GET", "/api/auth/csrf", csrf=False)
    token = json.loads(text).get("token")
    return status


def check(label, condition, detail=""):
    status = "PASS" if condition else "FAIL"
    if not condition:
        failures.append(label)
    print("[%s] %s%s" % (status, label, (" - " + detail) if detail else ""))


csrf_status = load_csrf_token()
check("GET /api/auth/csrf returns a token", csrf_status == 200 and bool(token),
      "token present: %s" % bool(token))

login_status, login_body = call("POST", "/api/auth/login",
                                {"username": "admin", "password": "admin"})
check("POST /api/auth/login as admin", login_status == 200, login_body[:120])
load_csrf_token()   # signing in replaces the session, so reload the CSRF token

status, body = call("GET", "/api/exams?status=draft")
check("GET /api/exams?status=draft", status == 200, "HTTP %s" % status)

status, body = call("GET", "/api/exams?status=archived")
check("unknown status is rejected with a readable message",
      status == 400 and "Unknown status" in body, body[:120])

status, body = call("GET", "/api/exams/conflicts")
check("GET /api/exams/conflicts", status == 200, "HTTP %s" % status)

status, body = call("POST", "/api/exams", {"examType": "Regular"})
check("exam without a subject name is rejected",
      status == 400 and "Subject name is required" in body, body[:120])

status, body = call("POST", "/api/exams", {
    "subjectName": "Smoke Test Physics", "examDate": "2026-11-02",
    "examTime": "11:00", "endTime": "09:00"})
check("exam with end time before start time is rejected (no 500)",
      status == 400 and "end time" in body, "HTTP %s %s" % (status, body[:120]))

status, body = call("POST", "/api/exams", {
    "subjectName": "Smoke Test Physics", "subjectCode": "SMK101",
    "examDate": "2026-11-02", "examTime": "10:00", "endTime": "12:00",
    "semester": 3, "examType": "Regular", "status": "PUBLISHED"})
created = json.loads(body) if status < 300 else {}
exam_id = created.get("id")
check("new exam is saved as DRAFT", status < 300 and created.get("status") == "DRAFT",
      body[:160])
check("duration derived from the times", created.get("durationMinutes") == 120,
      "duration=%s" % created.get("durationMinutes"))

status, body = call("PUT", "/api/exams/%s" % exam_id, {
    "subjectName": "Smoke Test Physics", "examDate": "2026-11-02",
    "examTime": "10:00", "endTime": "12:00", "semester": 3})
check("existing exam can be updated", status == 200, body[:120])

status, body = call("PUT", "/api/exams/%s/eligibility" % exam_id, {"batches": ["E", "E", "  ", "F"]})
check("eligibility stores unique non-blank batches", status == 200 and json.loads(body) == ["E", "F"],
      body[:120])
status, body = call("GET", "/api/exams/%s/eligibility" % exam_id)
check("eligibility round-trips", json.loads(body) == ["E", "F"], body[:120])

status, body = call("POST", "/api/exams", {"subjectName": "Smoke Test No Date"})
no_date_id = json.loads(body).get("id")
status, body = call("POST", "/api/exams/%s/status" % no_date_id, {"status": "PUBLISHED"})
check("publishing without a date/time is refused",
      status == 400 and "before publishing" in body, body[:120])
status, body = call("DELETE", "/api/exams/%s" % no_date_id)
check("draft exam without a date can be deleted", status == 200, body[:120])

status, body = call("POST", "/api/exams/%s/status" % exam_id, {"status": "published"})
check("exam can be published once dated", status == 200 and json.loads(body)["status"] == "PUBLISHED",
      body[:120])

status, body = call("DELETE", "/api/exams/%s" % exam_id)
check("published exam cannot be deleted",
      status == 400 and "Cancel the exam" in body, body[:120])

status, body = call("PUT", "/api/exams/%s" % exam_id, {
    "subjectName": "Smoke Test Physics", "examDate": "2026-11-09",
    "examTime": "10:00", "endTime": "12:00", "semester": 3})
check("editing the schedule of a published exam returns it to DRAFT",
      status == 200 and json.loads(body)["status"] == "DRAFT", body[:120])

status, body = call("POST", "/api/exams/%s/status" % exam_id, {"status": "CANCELLED"})
check("cancelling a draft exam works", status == 200 and json.loads(body)["status"] == "CANCELLED",
      body[:120])

status, body = call("DELETE", "/api/exams/%s" % exam_id)
check("cancelled exam can be deleted", status == 200 and json.loads(body).get("deleted") is True,
      body[:120])

status, _ = call("GET", "/api/exams/%s" % exam_id)
check("deleted exam is gone", status == 400, "HTTP %s" % status)

print("")
if failures:
    print("%d CHECK(S) FAILED: %s" % (len(failures), "; ".join(failures)))
    sys.exit(1)
print("ALL EXAM API CHECKS PASSED")
