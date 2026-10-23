#!/usr/bin/env python3
"""
Semester-management workflow test (admin + student) over real HTTP.

Covers the Semester 1-8 feature end to end:
  * admin creates students with a batch + semester (and validation of 1..8)
  * admin publishes an exam for a batch and one or more semesters
  * semester assignments persist (re-read through fresh sessions)
  * students only see the exams of their own batch + semester
    (dashboard / exam list / exam details - filtered on the backend)
  * seat publishing stays per-exam and per-student
  * same-slot conflict detection is semester aware

Usage:  python tools/test/semester-workflow-test.py http://localhost:8080
Creates its own data (rolls 901-904) - safe to re-run.
"""
import json
import sys
import urllib.error
import urllib.request
import http.cookiejar

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080").rstrip("/")
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

    def request(self, method, path, body=None, raw=False):
        data = json.dumps(body).encode() if body is not None else None
        headers = {"Content-Type": "application/json"} if body is not None else {}
        if method not in ("GET", "HEAD") and self.token:
            headers["X-XSRF-TOKEN"] = self.token
        req = urllib.request.Request(self.base + path, data=data, headers=headers, method=method)
        try:
            resp = self.opener.open(req)
            payload = resp.read()
            if raw:
                return resp.status, payload
            return resp.status, (json.loads(payload) if payload else None)
        except urllib.error.HTTPError as e:
            payload = e.read()
            try:
                parsed = json.loads(payload)
            except Exception:
                parsed = {"message": payload.decode(errors="replace")}
            if raw:
                return e.code, payload
            return e.code, parsed


def login(client, username, password):
    status, _ = client.request("POST", "/api/auth/login", {"username": username, "password": password})
    client.csrf()
    return status == 200


def main():
    admin = Client(BASE)
    admin.csrf()
    if not login(admin, "admin", "admin"):
        print("Cannot sign in as admin/admin - is the app running on " + BASE + "?")
        sys.exit(2)

    # ---------------------------------------------------------------- room
    status, room = admin.request("POST", "/api/rooms", {
        "roomNumber": "990", "rowsCount": 3, "columnsCount": 4,
        "studentsPerDesk": 1, "capacity": 12})
    if not room or not room.get("id"):
        # Re-run against a live server: reuse the room created by a previous run.
        _, rooms = admin.request("GET", "/api/rooms")
        room = next((r for r in (rooms or []) if r.get("roomNumber") == "990"), None)
    check("admin can add a room", room is not None and room.get("id"), status)

    # ---------------------------------------------------------------- students
    students = [
        {"rollNo": "901", "name": "Sem Two Student", "branch": "CEC", "batch": "E", "semester": 2},
        {"rollNo": "902", "name": "Sem Three Student", "branch": "CEC", "batch": "E", "semester": 3},
        {"rollNo": "903", "name": "Other Batch Student", "branch": "CEC", "batch": "F", "semester": 2},
        {"rollNo": "904", "name": "Legacy No Semester", "branch": "CEC", "batch": "E"},
    ]
    created = {}
    _, listing0 = admin.request("GET", "/api/students")
    existing_by_roll = {s["rollNo"]: s for s in (listing0 or [])}
    for payload in students:
        status, saved = admin.request("POST", "/api/students", payload)
        if not saved or not saved.get("id"):
            # Re-run against a live server: reuse the student from a previous run.
            saved = existing_by_roll.get(payload["rollNo"], saved)
        created[payload["rollNo"]] = saved
        check("admin creates student %s with batch %s semester %s"
              % (payload["rollNo"], payload["batch"], payload.get("semester")),
              bool(saved) and saved.get("semester") == payload.get("semester"),
              status)

    status, err = admin.request("POST", "/api/students",
                                {"rollNo": "905", "name": "Bad Sem", "batch": "E", "semester": 9})
    check("semester outside 1-8 is rejected on student create",
          status == 400 and "between 1 and 8" in (err.get("message") or ""), (status, err))

    status, listing = admin.request("GET", "/api/students?semester=2")
    rolls = sorted(s["rollNo"] for s in listing)
    check("admin student list filters by semester", status == 200 and rolls == ["901", "903"], rolls)

    status, listing = admin.request("GET", "/api/students")
    by_roll = {s["rollNo"]: s for s in listing}
    check("admin student list shows each semester",
          all(by_roll[r].get("semester") == v for r, v in
              {"901": 2, "902": 3, "903": 2, "904": None}.items()))

    # ---------------------------------------------------------------- exam publish
    status, exam = admin.request("POST", "/api/exams", {
        "subjectName": "Digital Logic SEMTEST", "branch": "CEC", "course": "E",
        "semesters": [2], "examDate": "2026-11-05", "examTime": "10:00"})
    exam_id = exam.get("id") if exam else None
    check("admin publishes an exam for batch E + semester 2",
          status == 200 and exam.get("semesters") == [2], (status, exam))
    check("legacy semester column stays in sync with the selection",
          exam.get("semester") == 2, exam)

    status, err = admin.request("POST", "/api/exams", {
        "subjectName": "Bad Sem Exam", "semesters": [0], "examDate": "2026-11-06", "examTime": "10:00"})
    check("semester outside 1-8 is rejected on exam create",
          status == 400 and "between 1 and 8" in (err.get("message") or ""), (status, err))

    # persistence: re-read through a fresh admin session
    refresh = Client(BASE)
    refresh.csrf()
    login(refresh, "admin", "admin")
    status, exams = refresh.request("GET", "/api/exams")
    stored = [e for e in exams if e["id"] == exam_id]
    check("semester assignment persists after a fresh session",
          status == 200 and stored and stored[0].get("semesters") == [2], stored)
    status, sems = refresh.request("GET", "/api/exams/%d/semesters" % exam_id)
    check("GET /api/exams/{id}/semesters returns the selection", status == 200 and sems == [2], sems)

    status, err = admin.request("PUT", "/api/exams/%d/semesters" % exam_id, {"semesters": [0, 9]})
    check("invalid semester selection is rejected on update",
          status == 400 and "between 1 and 8" in (err.get("message") or ""), (status, err))

    status, saved = admin.request("PUT", "/api/exams/%d/semesters" % exam_id, {"semesters": [2, 5, 2]})
    check("multi-semester selection is stored de-duplicated", status == 200 and saved == [2, 5], saved)
    admin.request("PUT", "/api/exams/%d/semesters" % exam_id, {"semesters": [2]})

    status, _ = admin.request("POST", "/api/exams/%d/status" % exam_id, {"status": "PUBLISHED"})
    check("admin can publish the exam", status == 200, status)

    # ---------------------------------------------------------------- student visibility
    stu = {roll: Client(BASE) for roll in ("901", "902", "903", "904")}
    for roll, client in stu.items():
        check("student %s can sign in" % roll, login(client, roll, roll))

    def sees_exam(client):
        status, exams = client.request("GET", "/api/student/exams")
        return status == 200 and any(e["id"] == exam_id for e in exams)

    check("student 901 (batch E, sem 2) sees the exam", sees_exam(stu["901"]))
    check("student 902 (batch E, sem 3) does NOT see the exam", not sees_exam(stu["902"]))
    check("student 903 (batch F, sem 2) does NOT see the exam", not sees_exam(stu["903"]))
    check("student 904 (batch E, no semester - legacy) sees the exam", sees_exam(stu["904"]))

    status, me = stu["901"].request("GET", "/api/student/me")
    check("student profile shows batch and semester",
          status == 200 and me.get("batch") == "E" and me.get("semester") == 2, me)
    status, dash = stu["901"].request("GET", "/api/student/dashboard")
    check("student dashboard shows semester and the upcoming exam",
          status == 200 and dash.get("semester") == 2 and dash.get("upcomingExams") == 1, dash)

    status, profile = stu["901"].request("PUT", "/api/student/profile", {"email": "s901@example.com"})
    check("student cannot change semester through the profile endpoint",
          status == 200 and "semester" not in json.dumps(
              {k: v for k, v in (profile or {}).items() if k != "message"}), profile)

    # ---------------------------------------------------------------- semester changes
    admin.request("PUT", "/api/exams/%d/semesters" % exam_id, {"semesters": [3]})
    check("after moving the exam to semester 3, student 901 no longer sees it",
          not sees_exam(stu["901"]))
    check("after moving the exam to semester 3, student 902 now sees it",
          sees_exam(stu["902"]))
    admin.request("PUT", "/api/exams/%d/semesters" % exam_id, {"semesters": [2]})

    # admin advances student 902 to semester 2 (progression)
    s902 = by_roll["902"]
    s902.update({"semester": 2})
    status, updated = admin.request("PUT", "/api/students/%d" % s902["id"],
                                    {k: s902.get(k) for k in
                                     ("rollNo", "name", "branch", "batch", "semester", "email", "phone", "accountStatus")})
    check("admin can move a student to another semester",
          status == 200 and updated.get("semester") == 2, (status, updated))
    check("student 902 now sees the exam after the semester change", sees_exam(stu["902"]))
    status, me = stu["902"].request("GET", "/api/student/me")
    check("the student profile reflects the new semester", me.get("semester") == 2, me)

    status, err = admin.request("PUT", "/api/students/%d" % s902["id"],
                                {k: s902.get(k) for k in ("rollNo", "name", "branch", "batch", "accountStatus")}
                                | {"semester": 12})
    check("semester outside 1-8 is rejected on student update",
          status == 400 and "between 1 and 8" in (err.get("message") or ""), (status, err))

    # ---------------------------------------------------------------- seating + publish
    status, result = admin.request("POST", "/api/allocate", {"examId": exam_id, "roomIds": [room["id"]]})
    check("seating chart generates for the semester-filtered audience",
          status == 200 and result.get("success") and result.get("eligibleStudents") == 3, result)

    status, _ = admin.request("POST", "/api/allocations/%d/publish" % exam_id, {})
    check("admin can publish the seating", status == 200, status)

    status, seat = stu["901"].request("GET", "/api/student/seat")
    check("student 901 sees the published seat for the exam",
          status == 200 and seat.get("seat") and seat["exam"]["id"] == exam_id, seat)
    status, seat = stu["903"].request("GET", "/api/student/seat")
    check("student 903 (other batch) has no seat for it",
          status == 200 and not seat.get("seat"), seat)

    status, pdf = admin.request("GET", "/api/chart/pdf?examId=%d" % exam_id, raw=True)
    check("chart PDF still generates", status == 200 and pdf[:4] == b"%PDF", status)

    # ---------------------------------------------------------------- notifications
    status, inbox = stu["901"].request("GET", "/api/student/notifications")
    types901 = [n["type"] for n in (inbox or {}).get("notifications", [])]
    check("student 901 got an exam-published notification",
          status == 200 and "EXAM_PUBLISHED" in types901, (status, types901))
    check("student 901 got a seat-published notification",
          status == 200 and "SEAT_PUBLISHED" in types901, (status, types901))
    check("unread count is exposed to the student",
          inbox.get("unreadCount", 0) >= 2, inbox)

    status, inbox903 = stu["903"].request("GET", "/api/student/notifications")
    check("student 903 (wrong batch) got NO notifications",
          status == 200 and not (inbox903 or {}).get("notifications"), inbox903)

    # duplicate prevention: re-publishing the same seating creates nothing new
    before = len([n for n in inbox["notifications"] if n["type"].startswith("SEAT_")])
    admin.request("POST", "/api/allocations/%d/publish" % exam_id, {})
    status, inbox_after = stu["901"].request("GET", "/api/student/notifications")
    after = len([n for n in inbox_after["notifications"] if n["type"].startswith("SEAT_")])
    check("re-publishing identical seating does not duplicate notifications",
          status == 200 and after == before, (before, after))

    # mark one read + all read
    first = inbox_after["notifications"][0]
    status, res = stu["901"].request("POST", "/api/student/notifications/%d/read" % first["id"], {})
    check("student can mark a notification read",
          status == 200 and res.get("read") is True, (status, res))
    status, res = stu["901"].request("POST", "/api/student/notifications/read-all", {})
    status, count = stu["901"].request("GET", "/api/student/notifications/unread-count")
    check("mark-all-read zeroes the unread count",
          status == 200 and count.get("unreadCount") == 0, (status, count))

    # cross-student isolation: 901 cannot mark 902's notification read
    status, inbox902 = stu["902"].request("GET", "/api/student/notifications")
    n902 = (inbox902 or {}).get("notifications") or []
    if n902:
        status, err = stu["901"].request("POST",
                                          "/api/student/notifications/%d/read" % n902[0]["id"], {})
        check("student cannot mark another student's notification read",
              status == 400, (status, err))
    else:
        check("student 902 has notifications to test isolation with", False, inbox902)

    # admin/student role separation on the new surfaces
    status, _ = admin.request("GET", "/api/student/notifications")
    check("admin session cannot use the student notification API", status == 403, status)
    status, _ = stu["901"].request("GET", "/api/admin/announcements")
    check("student session cannot use the admin announcement API", status == 403, status)

    # push device lifecycle (native Web Push subscription - fake endpoint; sending
    # itself is best-effort and never blocks registration)
    fake_subscription = {
        "endpoint": "https://push.example.test/e2e-sub-001",
        "keys": {"p256dh": "e2e-p256dh-key", "auth": "e2e-auth-key"},
    }
    status, res = stu["901"].request("POST", "/api/student/push/register",
                                     {"subscription": fake_subscription, "deviceInfo": "E2E"})
    check("student can register a push subscription", status == 200 and res.get("registered"), (status, res))
    status, res = stu["902"].request("POST", "/api/student/push/register",
                                     {"subscription": fake_subscription})
    check("another student re-registering the same device takes it over",
          status == 200 and res.get("registered"), (status, res))
    status, devices = stu["901"].request("GET", "/api/student/push/status")
    check("the taken-over device no longer belongs to student 901",
          status == 200 and not devices.get("devices"), devices)
    status, devices = stu["902"].request("GET", "/api/student/push/status")
    check("the device now belongs to student 902",
          status == 200 and len(devices.get("devices", [])) == 1, devices)
    device_id = ((devices or {}).get("devices") or [{}])[0].get("id")
    status, res = stu["901"].request("DELETE", "/api/student/push/unregister?id=" + str(device_id))
    check("a student cannot unregister a device they do not own", status == 400, (status, res))
    status, res = stu["902"].request("DELETE", "/api/student/push/unregister?id=" + str(device_id))
    check("student can unregister a device by id", status == 200 and res.get("unregistered"), (status, res))
    status, res = stu["901"].request("POST", "/api/student/push/register", {})
    check("registering without a subscription is rejected", status == 400, (status, res))

    # push status hands the browser only the PUBLIC VAPID key
    status, push_status = stu["902"].request("GET", "/api/student/push/status")
    vapid = (push_status or {}).get("vapidKey") or ""
    check("push status returns a public VAPID key for subscribe()",
          status == 200 and len(vapid) > 20, (status, len(vapid)))
    check("push status reports whether the server can send",
          status == 200 and isinstance((push_status or {}).get("pushEnabled"), bool), push_status)

    # ---------------------------------------------------------------- targeted announcements
    status, res = admin.request("POST", "/api/admin/announcements", {
        "title": "E2E sem-2 only", "body": "semester targeting",
        "batchName": "E", "semester": 2})
    recipients = (res or {}).get("recipients")
    check("announcement fans out to the targeted audience only",
          status == 200 and recipients == 2, (status, res))   # 901 + 902 (batch E, sem 2)
    status, inbox903 = stu["903"].request("GET", "/api/student/notifications")
    check("a semester-targeted announcement never reaches other students",
          not any("E2E sem-2 only" in (n.get("title") or "")
                  for n in (inbox903 or {}).get("notifications", [])), inbox903)

    status, res = admin.request("POST", "/api/admin/announcements", {
        "title": "E2E roll-only", "body": "individual targeting", "rollNos": " 901 , "})
    check("announcement can target specific roll numbers",
          status == 200 and (res or {}).get("recipients") == 1, (status, res))
    status, inbox902 = stu["902"].request("GET", "/api/student/notifications")
    check("a roll-number announcement does not reach other students",
          not any("E2E roll-only" in (n.get("title") or "")
                  for n in (inbox902 or {}).get("notifications", [])), inbox902)

    status, err = admin.request("POST", "/api/admin/announcements",
                                {"body": "title missing"})
    check("an announcement without a title is rejected", status == 400, (status, err))
    status, err = admin.request("POST", "/api/admin/announcements",
                                {"title": "Bad sem", "semester": 12})
    check("an announcement with an invalid semester is rejected", status == 400, (status, err))

    # ---------------------------------------------------------------- semester-aware conflicts
    status, other = admin.request("POST", "/api/exams", {
        "subjectName": "Clash Probe SEMTEST", "branch": "CEC", "course": "E",
        "semesters": [3], "examDate": "2026-11-05", "examTime": "10:00"})
    other_id = other.get("id")
    status, conflicts = admin.request("GET", "/api/exams/conflicts")
    check("same slot for a different semester is NOT a conflict",
          status == 200 and not any(c["examId"] in (exam_id, other_id) and c["otherExamId"] in (exam_id, other_id)
                                    for c in conflicts), conflicts)
    admin.request("PUT", "/api/exams/%d/semesters" % other_id, {"semesters": [2]})
    status, conflicts = admin.request("GET", "/api/exams/conflicts")
    check("same slot for the SAME semester IS a conflict",
          status == 200 and any({c["examId"], c["otherExamId"]} == {exam_id, other_id} for c in conflicts),
          conflicts)

    # cleanup probe exam so re-runs stay clean
    admin.request("DELETE", "/api/exams/%d" % other_id)

    # ------------------------------------------------------- cancel + delete lifecycle
    status, _ = admin.request("POST", "/api/exams/%d/status" % exam_id, {"status": "CANCELLED"})
    check("admin can cancel the published exam", status == 200, status)

    status, inbox = stu["901"].request("GET", "/api/student/notifications")
    check("cancelling notifies the exam's eligible students",
          status == 200 and any(n["type"] == "EXAM_CANCELLED"
                                for n in inbox.get("notifications", [])),
          [n["type"] for n in (inbox or {}).get("notifications", [])])

    status, exams = stu["901"].request("GET", "/api/student/exams")
    listed = [e for e in exams if e["id"] == exam_id]
    check("the cancelled exam still appears in the student's exam list (with its badge)",
          status == 200 and listed and listed[0]["status"] == "CANCELLED", listed)

    status, dash = stu["901"].request("GET", "/api/student/dashboard")
    check("a cancelled exam is not counted as upcoming on the dashboard",
          status == 200 and dash.get("upcomingExams") == 0, dash)

    # Regression: an exam whose chart was generated holds exam_rooms rows with an
    # FK to it - deleting it used to fail with "Data conflict: duplicate or invalid data".
    status, res = admin.request("DELETE", "/api/exams/%d" % exam_id)
    check("an exam with a generated seating chart deletes cleanly (no data conflict)",
          status == 200 and isinstance(res, dict) and res.get("deleted") is True, (status, res))

    failed = [name for name, ok in RESULTS if not ok]
    print("\n%d checks, %d failed" % (len(RESULTS), len(failed)))
    if failed:
        for name in failed:
            print("  FAILED: " + name)
        sys.exit(1)
    print("All semester workflow checks passed.")


if __name__ == "__main__":
    main()
