#!/usr/bin/env python3
"""Seat-fairness (2-times) end-to-end test over real HTTP.

The allocation rule "no student sits in the same room or at the same desk
position more than two times" is exercised through the real API:

  * exam 1 and exam 2 both put everyone in room A      (1st and 2nd time - fine)
  * exam 3 offers rooms A + B  -> everyone must land in B (A is now at the cap)
  * exam 4 offers only room A  -> everyone is seated anyway (attendance first),
    repeatViolations is reported and the warnings contain the 2-times note

Unique rolls/rooms per run make the script safe to re-run, and everything it
creates is removed again in a finally block. Exits non-zero on any failure.

Usage:  python tools/test/seat-repeat-test.py [http://localhost:8080]
        APP_URL=http://localhost:8081 python tools/test/seat-repeat-test.py
"""
import http.cookiejar
import json
import os
import sys
import time
import urllib.error
import urllib.request

BASE = (sys.argv[1] if len(sys.argv) > 1
        else os.environ.get("APP_URL", "http://localhost:8080")).rstrip("/")
RESULTS = []


def check(name, cond, detail=""):
    RESULTS.append((name, bool(cond)))
    print(("PASS  " if cond else "FAIL  ") + name + (("  -- " + str(detail)) if (detail and not cond) else ""))


class Client:
    def __init__(self, base):
        self.base = base
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        self.token = ""

    def csrf(self):
        _, data = self.request("GET", "/api/auth/csrf")
        self.token = (data or {}).get("token", "")

    def request(self, method, path, body=None):
        data = json.dumps(body).encode() if body is not None else None
        headers = {"Content-Type": "application/json"} if body is not None else {}
        if method not in ("GET", "HEAD") and self.token:
            headers["X-XSRF-TOKEN"] = self.token
        req = urllib.request.Request(self.base + path, data=data, headers=headers, method=method)
        try:
            resp = self.opener.open(req)
            payload = resp.read()
            return resp.status, (json.loads(payload) if payload else None)
        except urllib.error.HTTPError as e:
            payload = e.read()
            try:
                parsed = json.loads(payload)
            except Exception:
                parsed = {"message": payload.decode(errors="replace")}
            return e.code, parsed


def exam_allocations(admin, exam_id):
    status, rows = admin.request("GET", "/api/allocations?examId=%d" % exam_id)
    if status != 200 or not isinstance(rows, list):
        return None
    return [r for r in rows if r.get("examId") == exam_id]


def main():
    stamp = str(int(time.time()))
    tag = "RP" + stamp[-6:]
    admin = Client(BASE)
    admin.csrf()
    status, _ = admin.request("POST", "/api/auth/login", {"username": "admin", "password": "admin"})
    admin.csrf()
    if status != 200:
        print("Cannot sign in as admin/admin - is the app running on " + BASE + "?")
        return 2

    created = {"students": [], "exams": [], "rooms": []}
    try:
        # ---------------------------------------------------------------- setup
        for n in range(1, 5):
            roll = "%s%d" % (tag, n)
            status, body = admin.request("POST", "/api/students", {
                "rollNo": roll, "name": "Repeat Student %d" % n,
                "branch": "QA", "batch": tag, "semester": 2})
            check("student %s created" % roll, status in (200, 201) and (body or {}).get("id"), (status, body))
            if (body or {}).get("id"):
                created["students"].append(body["id"])

        room_ids = []
        for label in ("A", "B"):
            status, body = admin.request("POST", "/api/rooms", {
                "roomNumber": "RP%s-%s" % (label, stamp), "name": "Repeat Room " + label,
                "rowsCount": 2, "columnsCount": 2, "studentsPerDesk": 1,
                "capacity": 4, "active": True})
            check("room %s created (4 seats)" % label, status in (200, 201) and (body or {}).get("id"), (status, body))
            if (body or {}).get("id"):
                room_ids.append(body["id"])
                created["rooms"].append(body["id"])
        if len(room_ids) < 2:
            return 1

        exam_ids = []
        for n in range(1, 5):
            status, body = admin.request("POST", "/api/exams", {
                "subjectName": "Repeat Exam %d %s" % (n, stamp),
                "branch": "QA", "course": tag,
                "examDate": "2027-01-0%d" % n, "examTime": "1%d:00" % (n + 1),
                "endTime": "1%d:30" % (n + 1)})
            check("exam %d created as draft" % n, status in (200, 201) and (body or {}).get("id"), (status, body))
            if (body or {}).get("id"):
                exam_ids.append(body["id"])
                created["exams"].append(body["id"])
        if len(exam_ids) < 4:
            return 1

        # ---------------------------------------------------------------- exam 1: fresh room A
        status, res = admin.request("POST", "/api/allocate",
                                    {"examId": exam_ids[0], "roomIds": [room_ids[0]]})
        check("exam 1 allocates everyone in room A",
              status == 200 and (res or {}).get("success") and res.get("allocated") == 4, (status, res))
        check("the result states the seat-fairness rule",
              "Seat-fairness" in (res.get("message") or ""), res.get("message"))
        base_history = (res or {}).get("historySeatings")
        check("first run analyses zero earlier seatings for this data",
              base_history is not None and base_history >= 0, res)
        check("no violation on a fresh room", (res or {}).get("repeatViolations") == 0, res)
        rows = exam_allocations(admin, exam_ids[0])
        check("exam 1 allocations all point at room A",
              rows is not None and len(rows) == 4 and all(r.get("roomId") == room_ids[0] for r in rows), rows)

        # ---------------------------------------------------------------- exam 2: second time in A is fine
        status, res = admin.request("POST", "/api/allocate",
                                    {"examId": exam_ids[1], "roomIds": [room_ids[0]]})
        check("exam 2 allocates everyone in room A again (2nd time allowed)",
              status == 200 and (res or {}).get("success") and res.get("allocated") == 4, (status, res))
        check("exam 2 counted exam 1's 4 seatings as history",
              (res or {}).get("historySeatings") == base_history + 4, res)
        check("still no violation at the cap", (res or {}).get("repeatViolations") == 0, res)
        rows = exam_allocations(admin, exam_ids[1])
        check("exam 2 allocations all point at room A",
              rows is not None and len(rows) == 4 and all(r.get("roomId") == room_ids[0] for r in rows), rows)

        # ---------------------------------------------------------------- exam 3: A is at the cap -> B
        status, res = admin.request("POST", "/api/allocate",
                                    {"examId": exam_ids[2], "roomIds": [room_ids[0], room_ids[1]]})
        check("exam 3 allocates everyone",
              status == 200 and (res or {}).get("success") and res.get("allocated") == 4, (status, res))
        check("exam 3 analysed both earlier charts (8 seatings)",
              (res or {}).get("historySeatings") == base_history + 8, res)
        rows = exam_allocations(admin, exam_ids[2])
        check("no student sits in room A a third time - all seats are in room B",
              rows is not None and len(rows) == 4 and all(r.get("roomId") == room_ids[1] for r in rows), rows)
        check("room A is not counted as used", (res or {}).get("roomsUsed") == 1, res)
        check("no violation when a free alternative room exists",
              (res or {}).get("repeatViolations") == 0, res)
        check("no 2-times warning when the rule held",
              not any("2-times" in str(c) for c in (res or {}).get("conflicts") or []), res)

        # ---------------------------------------------------------------- exam 4: only A available -> repeat + report
        status, res = admin.request("POST", "/api/allocate",
                                    {"examId": exam_ids[3], "roomIds": [room_ids[0]]})
        check("exam 4 still seats everyone (attendance first)",
              status == 200 and (res or {}).get("success") and res.get("allocated") == 4, (status, res))
        check("exam 4 reports 4 unavoidable repeats",
              (res or {}).get("repeatViolations") == 4, res)
        check("the warnings mention the 2-times limit",
              any("2-times" in str(c) for c in (res or {}).get("conflicts") or []), res)
        rows = exam_allocations(admin, exam_ids[3])
        check("exam 4 seated everyone in the only offered room",
              rows is not None and len(rows) == 4 and all(r.get("roomId") == room_ids[0] for r in rows), rows)
        check("each student still has exactly one seat per exam",
              rows is not None and len({r.get("studentId") for r in rows}) == 4, rows)
    finally:
        # Best-effort cleanup: only the records this run created.
        for exam_id in created["exams"]:
            admin.request("DELETE", "/api/exams/%d" % exam_id)
        for room_id in created["rooms"]:
            admin.request("DELETE", "/api/rooms/%d" % room_id)
        for student_id in created["students"]:
            admin.request("DELETE", "/api/students/%d" % student_id)

    failed = [name for name, ok in RESULTS if not ok]
    print("\n%d checks, %d failed" % (len(RESULTS), len(failed)))
    if failed:
        for name in failed:
            print("  FAILED: " + name)
        return 1
    print("All seat-fairness (2-times) checks passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
