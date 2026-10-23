# Exam Room and Seat Allocation Management System

A web application that automates exam seating: it stores students (Roll No, Name,
Branch, Batch/Course), rooms and exams, generates a conflict-free seating chart
for every exam, detects scheduling clashes, and exports the final timetable as a
PDF that includes the student list with Roll Number, Name, Branch and Batch.

## Key features

- **Student management** — exactly four fields per student: Roll Number, Name,
  Branch, Batch (no Division field), plus an optional **Semester (1–8)**.
- **Semester targeting** — students carry a current Semester 1–8 and exams are
  published to a batch plus one or more semesters; students only see the exams
  of their own batch and semester (filtered on the server, not just the UI).
- **Course/Batch management** — default batches `A, B, C, D, E, F, G` seeded
  automatically, plus an **Add Course** button for custom batches that become
  immediately available to the student form, exam form and scheduling.
- **Exam-data upload** — import exam schedules from **PDF, Excel (.xlsx/.xls) or
  CSV**; subject, branch, batch, date, time and application ID are
  extracted, validated (rows without a subject or a parseable date are rejected
  and reported) and stored in the database for timetable generation.
- **Student-list upload** — import student lists from the same formats.
- **Room-list upload** — from the **Import Data → Rooms** tab, import room
  numbers and desk counts from PDF, Excel (.xlsx/.xls) or CSV; rows, columns and
  capacity are calculated automatically.
- **Seating allocation** — fills each bench to its configured student limit,
  prioritizes larger selected rooms to reduce partially used rooms, interleaves
  branch·batch groups, and is re-runnable and idempotent.
- **Conflict detection** — same date + time + audience exam clashes, plus
  warnings when a student or room is double-booked by the generated chart.
- **PDF export** — landscape A4 seating chart (colour-coded by branch·batch)
  with a full **Student List** table: Roll No | Name | Branch | Batch |
  Room | Seat.
- **Two role-based portals** — an administrator SPA (Dashboard, Students, Rooms,
  Courses, Exams, Seating Chart, Import Data, Calendar, Reports, Announcements,
  Settings) and a student portal (Overview, My Exams, My Seat, Profile).
- **Student notifications** — exam, seat, room and admin announcement events
  fan out to the targeted students' inboxes; while signed in, Java Server-Sent
  Events deliver alerts in real time and update the bell's unread badge. After
  the student grants permission once (only ever from an explicit button click),
  **native Web Push (VAPID, self-hosted)** delivers the alert even with the
  portal closed — with the app icon, heading, message and a "view details"
  link straight to the right portal section. The **Settings tab edits the
  default wording** of every notification type (exam published/rescheduled/
  cancelled, seat published/changed, room updated, announcement) with
  placeholders like `{subject}`, `{seat}` and `{when}`. No Firebase and no
  third-party push service is involved.
- **Authentication & roles** — server-side login for the administrator and for
  every student (roll number as initial password, a banner prompting a password
  change on first login, BCrypt hashes), session cookies, CSRF protection, login
  rate limiting and an audit trail of admin actions. Students can only access
  their own records; every admin API rejects student sessions.
- **Room management** — optional room **name** (dash shown when empty),
  building/floor, live **search** by number/name/building, an availability
  **Select** checkbox persisted in MySQL and enforced by the allocator
  (inactive rooms are never used for new charts), plus a **room picker** on the
  Seating Chart tab that shows total selected capacity and warns when it is
  short of the eligible-student count.
- **Responsive, mobile-first UI** — fluid add/edit form grid (6 → 3 → 2 → 1
  columns), tab strip that scrolls inside its own container, tables wrapped in
  dedicated scroll areas and touch-friendly controls: verified with **no
  page-level horizontal overflow at 1920/1366/1024/768/390/320 px**.

## Technology

| Layer     | Technology |
|-----------|------------|
| Backend   | Java 17+ (tested on Java 25), Spring Boot 3.2 (Web, Data JPA, Security) |
| Database  | MySQL 8 by default (Docker Compose); MySQL/PostgreSQL drivers included |
| PDF       | Apache PDFBox 2.0.31 (generation **and** text extraction for import) |
| Excel     | Apache POI 5.2.5 |
| Frontend  | Single-page HTML/CSS/vanilla JS served from `src/main/resources/static/` (`index.html` + `app.js`) |
| Build     | Maven (`./.mvn-tool/apache-maven-3.9.9/bin/mvn` bundled) |

## Run it

```bash
#1. Build
./.mvn-tool/apache-maven-3.9.9/bin/mvn clean package -DskipTests

#2. Start MySQL
docker compose up -d

#3. (Optional) configure via environment variables
#    copy .env.example to .env and edit; without it the docker-compose defaults are used
cp .env.example .env

#4. Start the app (port 8080 by default; DB defaults match docker-compose.yml)
java -jar target/room-allocation-system-0.0.1-SNAPSHOT.jar

#5. Open the UI and sign in
#    http://localhost:8080
#    Administrator: admin / admin      (development-only default)
#    Student:       roll number as both username and password
```

At startup, the application prints its localhost URL and detected private
IPv4/LAN URLs in the terminal. Other devices on the same network can use a LAN
URL; allow the Java application through the host firewall if the page cannot be
reached.

Credentials, database name and the admin seed account are all configurable
through the environment variables listed in `.env.example` (`DB_URL`,
`DB_USERNAME`, `DB_PASSWORD`, `APP_ADMIN_USERNAME`, `APP_ADMIN_PASSWORD`).
Serve the app over HTTPS in production and change the demo admin password
before deployment.

### Background push to student phones (native Web Push)

The Java server sends browser push itself over the standard Web Push protocol
(RFC 8291 payload encryption + RFC 8292 VAPID signatures) — the same
self-hosted design as the companion `notification/` tooling, with no Firebase
and no third-party push service involved.

1. Generate a VAPID key pair once and export it before starting the server
   (any Web Push VAPID generator works, for example
   `npx web-push generate-vapid-keys`, or `npm run generate:vapid` in the
   `notification/` folder):

   ```bash
   VAPID_PUBLIC_KEY=<public key>      # handed to the browser - public by design
   VAPID_PRIVATE_KEY=<private key>    # SECRET: signs requests, stays server-side
   VAPID_SUBJECT=mailto:admin@college.example
   ```

   When the keys are absent the server generates an **ephemeral** pair at
   startup for local development and logs a warning — subscriptions then break
   on every restart until real keys are configured.

2. Students sign in from each phone/browser, open the notification bell and
   press **Enable notifications** (permission is only ever requested from that
   click), allow notifications, and the browser subscription is stored in the
   SQL `push_subscriptions` table (endpoint + `p256dh`/`auth` keys), scoped to
   the signed-in student's ID.

3. Publishing an exam, publishing seating, editing a room or posting an
   announcement renders the matching message from the `notification_templates`
   table (admin-editable in **Settings**, resettable to the built-in defaults)
   and sends it to each recipient's active subscriptions — best-effort with
   bounded retry; subscriptions the push service reports as gone (HTTP 404/410)
   are deactivated automatically.

This is web push, not a native Android/iOS app notification; real-time updates
in an open portal use Java Server-Sent Events and need no keys at all.
Phone browsers require a secure HTTPS origin for service workers and Web Push.
The `http://localhost` URL is secure for local development, but an HTTP LAN IP
address is not; use an HTTPS domain/reverse proxy or a trusted HTTPS certificate
for phones on the network.

Notification content and recipients are stored separately in `notifications`;
each push only looks up the student's rows in `push_subscriptions`, so
subscription keys are never copied into notification rows.

Connect MySQL Workbench to `127.0.0.1:3306` with username `root` and local
development password `Akshay@6235`; refresh Schemas to browse `room_allocation_db`
(see [mysql-commands.md](mysql-commands.md) for SQL commands to show the data).
The app accepts `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD` environment variables
to override its MySQL connection settings. See `database.md` for details.

## Typical workflow

1. **Students** tab — add students (Roll No, Name, Branch, Batch, Semester) or import a list.
2. **Rooms** tab — add rooms manually.
3. **Courses** tab — keep the defaults A–G or add custom batches.
4. **Import Data** tab — upload students, rooms or the exam schedule as PDF/Excel/CSV (or create exams manually).
5. **Exams** tab — review exams and any reported scheduling conflicts.
6. **Seating Chart** tab — generate the chart, check conflict warnings, download the PDF.

## Tests

```bash
# Unit tests (JUnit + Mockito — runs on Java 25; Byte Buddy / Mockito are
# pinned in pom.xml to versions that support class-file version 69)
# 82 tests: exam lifecycle, notifications + push fan-out, VAPID keys,
# push templates, allocation, conflicts, imports and more
./.mvn-tool/apache-maven-3.9.9/bin/mvn test

# Exam API smoke test over HTTP against a running server (20 checks)
python tools/test/exam-api-smoke.py http://localhost:8080

# Semester workflow test (admin + student, 40 checks)
python tools/test/semester-workflow-test.py http://localhost:8080

# Duplicate-seat guarantee (self-contained: creates and cleans up its own data)
python tools/test/duplicate-seat-test.py http://localhost:8080

# Seed baseline data (signs in as admin and handles CSRF; app on :8081)
bash tools/test/seed.sh http://localhost:8081/api

# End-to-end UI suite through Chrome DevTools Protocol (signs in as admin first)
# (start Chrome first: chrome.exe --headless=new --remote-debugging-port=9333 --user-data-dir=%TEMP%/chrome-test http://localhost:8081/)
node tools/test/cdp-test.mjs

# Rooms + responsiveness suite: room name/search/Select, allocation room
# picker, and horizontal-overflow sweep at 1920→320px (default app :8080)
node tools/test/responsive-test.mjs

# Accessibility/responsive/auth UX suite (same APP_URL override works)
APP_URL=http://localhost:8081/ node tools/test/responsible-test.mjs
```

Fixture generators live next to the tests: `make_xlsx.py`, `make_exam_xlsx.py`,
`MakePdf.java`, `MakeExamPdf.java`. `tools/test/responsible-test.mjs` covers the
accessibility/responsive/auth UX checks, and `duplicate-seat-test.py` verifies no
seat is allocated twice.

## Documentation

- `PROJECT_DETAILS.md` — full architecture, database, API, scheduling, PDF and
  developer guide (30 sections).
- `database.md` — database configuration and entity/table mapping.
- `plan.md` — original project plan.
- `todo.md` — implementation status of the requirements.
