#!/usr/bin/env python3
"""End-to-end duplicate-seat guarantee test.

Creates two dedicated students, one dedicated exam and one dedicated room
through the real HTTP API, generates the chart, then asserts:

  * no two SeatAllocation rows share the same seat in one exam,
  * no student is allocated twice in the same exam,
  * every allocation points at the room that was selected,
  * allocations never exceed the room capacity,
  * every allocated seat belongs to this exam.

All test data is removed again in a ``finally`` block, so the database is left
as it was found. Exits non-zero when any check fails.

Usage:  python tools/test/duplicate-seat-test.py [http://localhost:8080]
        APP_URL=http://localhost:8081 python tools/test/duplicate-seat-test.py
"""
import http.cookiejar
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = os.environ.get("APP_URL", "http://localhost:8080").rstrip("/")
jar = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))

token = None
failures = []


def call(method, path, payload=None, csrf=True):
    """Call the API, returning (status, parsed JSON or None)."""
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    request = urllib.request.Request(BASE + path, data=data, method=method)
    if data is not None:
        request.add_header("Content-Type", "application/json")
    if csrf and token:
        request.add_header("X-XSRF-TOKEN", str(token))
    try:
        with opener.open(request, timeout=30) as response:
            raw = response.read().decode("utf-8")
            return response.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as error:
        raw = error.read().decode("utf-8")
        try:
            return error.code, (json.loads(raw) if raw else None)
        except ValueError:
            return error.code, {"raw": raw}


def check(ok, label, detail=""):
    print(("[PASS] " if ok else "[FAIL] ") + label + ((" - " + str(detail)) if detail else ""))
    if not ok:
        failures.append(label)


def main():
    global token

    print("=== duplicate-seat guarantee test (self-contained) ===\n")

    status, body = call("POST", "/api/auth/login",
                        {"username": "admin", "password": "admin"}, csrf=False)
    check(status == 200 and isinstance(body, dict) and body.get("role") == "ADMIN",
          "admin login", body)
    if failures:
        return 1

    status, body = call("GET", "/api/auth/csrf")
    token = (body or {}).get("token") if isinstance(body, dict) else None
    check(status == 200 and bool(token), "CSRF token issued")
    if failures:
        return 1

    stamp = str(int(time.time()))
    tag = "QA" + stamp[-6:]
    created = {"students": [], "exam": None, "room": None}

    try:
        # 1) Two dedicated active students in a unique branch/batch.
        for number in (1, 2):
            roll = f"{tag}{number}"
            status, body = call("POST", "/api/students", {
                "rollNo": roll,
                "name": f"QA Student {number}",
                "branch": "QA",
                "batch": tag,
            })
            check(status in (200, 201) and isinstance(body, dict) and body.get("id"),
                  f"student {roll} created", body)
            if isinstance(body, dict) and body.get("id"):
                created["students"].append(body["id"])

        # 2) Dedicated exam targeting exactly those students.
        status, body = call("POST", "/api/exams", {
            "subjectName": f"Duplicate Seat {stamp}",
            "branch": "QA",
            "course": tag,
            "examDate": "2027-01-01",
            "examTime": "09:00",
            "endTime": "10:00",
        })
        check(status in (200, 201) and isinstance(body, dict) and body.get("id"),
              "exam created as draft", body)
        if not (isinstance(body, dict) and body.get("id")):
            return 1
        exam_id = body["id"]
        created["exam"] = exam_id
        check(body.get("status") == "DRAFT", "new exam starts as DRAFT", body.get("status"))

        # 3) Dedicated room: 2 x 2 desks, 2 students per desk = 8 seats.
        rows, columns, per_desk = 2, 2, 2
        capacity = rows * columns * per_desk
        status, body = call("POST", "/api/rooms", {
            "roomNumber": f"QA-{stamp}",
            "name": "QA Room",
            "rowsCount": rows,
            "columnsCount": columns,
            "studentsPerDesk": per_desk,
            "capacity": capacity,
            "active": True,
        })
        check(status in (200, 201) and isinstance(body, dict) and body.get("id"),
              "room created", body)
        if not (isinstance(body, dict) and body.get("id")):
            return 1
        room_id = body["id"]
        created["room"] = room_id

        # 4) Generate the chart for this exam and this room only.
        status, body = call("POST", "/api/allocate",
                            {"examId": exam_id, "roomIds": [room_id]})
        check(status == 200 and isinstance(body, dict) and body.get("success"),
              "allocation generated", body)
        if isinstance(body, dict):
            check(body.get("allocated") == 2, "both students seated", body.get("allocated"))

        # 5) Read the stored allocations back.
        status, allocations = call("GET", f"/api/allocations?examId={exam_id}")
        check(status == 200 and isinstance(allocations, list),
              "GET /api/allocations", status)
        exam_rows = [a for a in allocations if a.get("examId") == exam_id] \
            if isinstance(allocations, list) else []

        check(len(exam_rows) == 2, "two allocations stored for the exam", len(exam_rows))
        check(all(a.get("roomId") == room_id for a in exam_rows),
              "all allocations use the selected room")
        check(len({a.get("seatCode") for a in exam_rows}) == len(exam_rows),
              "no two allocations share a seat", [a.get("seatCode") for a in exam_rows])
        check(len({a.get("studentId") for a in exam_rows}) == len(exam_rows),
              "no student is allocated twice")
        check(len(exam_rows) <= capacity, "allocations within room capacity",
              f"{len(exam_rows)} <= {capacity}")

        # 6) Every allocation must reference a real seat of this exam.
        status, seats = call("GET", f"/api/seats?examId={exam_id}")
        check(status == 200 and isinstance(seats, list), "GET /api/seats", status)
        seat_rows = seats if isinstance(seats, list) else []
        seats_for_room = [s for s in seat_rows
                          if isinstance(s.get("room"), dict) and s["room"].get("id") == room_id]
        check(len(seats_for_room) == capacity,
              "room grid has one seat per position", len(seats_for_room))

        # /api/allocations exposes row/column/deskPosition/seatCode (not seatId),
        # so match each allocation to a seat by its physical coordinates.
        seat_keys = {(s.get("room", {}).get("id"), s.get("rowNumber"),
                      s.get("columnNumber"), s.get("deskPosition"))
                     for s in seat_rows if isinstance(s.get("room"), dict)}
        allocation_keys = {(a.get("roomId"), a.get("row"),
                            a.get("column"), a.get("deskPosition")) for a in exam_rows}
        check(allocation_keys <= seat_keys,
              "every allocation references a seat of this exam",
              sorted(allocation_keys - seat_keys))
        allocated_seats = [s for s in seat_rows if s.get("allocatedStudent")]
        check(len(allocated_seats) == len(exam_rows),
              "seats and allocations agree", f"{len(allocated_seats)} vs {len(exam_rows)}")
    finally:
        # Best-effort cleanup: only the records this test created.
        if created["exam"]:
            call("DELETE", f"/api/exams/{created['exam']}")
        if created["room"]:
            call("DELETE", f"/api/rooms/{created['room']}")
        for student_id in created["students"]:
            call("DELETE", f"/api/students/{student_id}")

    print()
    if failures:
        print(f"FAILED: {len(failures)} check(s): {', '.join(failures)}")
        return 1
    print("=== PASS: no duplicate seats ===")
    return 0


if __name__ == "__main__":
    sys.exit(main())
