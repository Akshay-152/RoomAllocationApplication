# PROJECT DETAILS — Exam Room and Seat Allocation Management System

> Complete technical documentation of the project as it is **actually implemented
> in this repository**. Everything below was checked against the source code on
> 2026-10-18; the Maven build was run in the same session (`mvn test` = **20
> tests, 0 failures**, including on Java 25).
> Anything not implemented is explicitly marked **Not Implemented** or **Planned**.

---

# 1. Project Overview

### What the project does
A web-based **Exam Room and Seat Allocation Management System** with two signed-in
experiences:

- **Administrator** — manages students, rooms, batches/courses, exams,
  announcements, departments and subjects, imports data from PDF/Excel/CSV,
  generates seating charts, publishes them and downloads/prints the PDF.
- **Student** — signs in with their roll number, sees their eligible exams,
  published seat (room, seat code, instructions) and updates contact details /
  password.

### Main purpose
Replace slow, error-prone manual exam seating work with a centralized,
automatic, conflict-checked and malpractice-resistant seating arrangement that
students can check online.

### Who uses it
- **Exam coordinators / college admin staff** — the administrator role.
- **Students** — read-only access to their own exams and seat, plus profile
  self-service.
- **Developers / students** — as a Spring Boot 3 + Spring Security + JPA + PDFBox
  reference project.

### Main problems it solves
1. Manual seating takes hours and is hard to re-run when anything changes.
2. Same-course students sitting next to each other (copying risk).
3. Scheduling clashes (two papers at the same date/time for the same batch).
4. Students not knowing their room/seat, and staff publishing stale charts.
5. Missing or inconsistent student information on the final timetable PDF.

### Overall workflow (input → final PDF)
```
Student list (form / PDF / Excel / CSV)      Exam schedule (form / PDF / Excel / CSV)
              ↓                                          ↓
        Student table + login account                Exam table (DRAFT → PUBLISHED)
              └──────────────┬───────────────────────────┘
                             ↓
              Seating allocation (round-robin + desk mixing)  → draft allocations
                             ↓
                 Conflict checks (students / rooms / slots)
                             ↓
              Admin review → Publish → student sees the seat
                             ↓
              Seating chart on screen + PDF download
```

### Key features
- Student core data is **Roll Number, Name, Branch, Batch/Course**; optional
  department, semester, email and phone are supported for the dashboards.
- Default batches **A–G** + custom batches through an **Add Course** button.
- Exam-data, student-list and room-list upload via **PDF / Excel / CSV**.
- Automatic seating chart generation (re-runnable, idempotent).
- Scheduling-conflict detection (schedule level + allocation level).
- Exam lifecycle **DRAFT → PUBLISHED → COMPLETED / CANCELLED**, eligibility
  per batch, per-semester targeting (Semester 1–8) and draft/published seat
  allocations. Students only see the exams of their own batch and semester.
- Final PDF: colour-coded seating grid **and** a student roster table
  (Roll No | Name | Branch | Batch | Room | Seat).
- **Authentication & roles** — Spring Security with BCrypt, JSON login, sessions,
  CSRF cookies, login rate limiting and an audit trail.
- Admin **Dashboard, Calendar, Reports, Announcements and Settings** tabs, plus a
  separate responsive **student portal**.

---

# 2. Technology Stack

| Technology | Where | What it is used for |
|---|---|---|
| **Java 17** (builds and tests on JDK 17–25) | `pom.xml` `<java.version>` | Main programming language |
| **Spring Boot 3.2.0** | `pom.xml` parent | Application framework (REST API, auto-configuration, embedded server) |
| **Spring Web** | `spring-boot-starter-web` | REST controllers, multipart file upload |
| **Spring Data JPA** | `spring-boot-starter-data-jpa` | ORM layer, repositories, transactions |
| **Spring Security** | `spring-boot-starter-security` | Login filter chain, role rules, sessions, CSRF, BCrypt |
| **Hibernate** | via Spring Data JPA | JPA implementation, automatic DDL (`ddl-auto=update`) |
| **MySQL 8** | `spring.datasource.url=jdbc:mysql:...` | Default database — local instance provided by Docker Compose |
| **H2 (in-memory)** | `h2` (runtime) | Available for explicitly configured tests/demo use |
| **MySQL connector** | `mysql-connector-j` (runtime) | Default database driver |
| **PostgreSQL driver** | `postgresql` (runtime) | Optional production database |
| **Apache PDFBox 2.0.31** | `pdfbox` | PDF **generation** plus PDF **text extraction** for imports |
| **Apache POI 5.2.5** | `poi-ooxml` | Reading Excel `.xlsx` / `.xls` uploads |
| **HTML + CSS + vanilla JS** | `static/index.html` + `static/app.js` | SPA: admin portal, student portal, login screen (no framework/build step) |
| **Maven** | `pom.xml`, bundled in `.mvn-tool/apache-maven-3.9.9` | Build, dependency management, packaging |
| **JUnit 5 + Mockito 5.24 + Byte Buddy 1.17.7** | `src/test`, `pom.xml` properties | Unit tests (pinned so they run on Java 25) |
| **Chrome DevTools Protocol** | `tools/test/cdp-test.mjs`, `responsive-test.mjs`, `responsible-test.mjs` | End-to-end UI suites (no npm dependencies) |
| **Python 3** | `tools/test/exam-api-smoke.py`, `duplicate-seat-test.py` | HTTP smoke tests |
| **Docker Compose** | `docker-compose.yml` | Optional local MySQL 8 instance |

---

# 3. Complete Project Structure

```text
room-allocation-system/
├── README.md                     # Quick start + feature overview
├── PROJECT_DETAILS.md            # This document
├── todo.md                       # Requirement + verification status
├── plan.md                       # Original project plan
├── database.md / mysql-commands.md
├── responsible.md / make edit responsive.md   # UI/UX + security specs
├── docker-compose.yml            # Optional MySQL 8 for local runs
├── .env.example                  # DB/admin/server environment template
├── pom.xml
├── .mvn-tool/apache-maven-3.9.9/ # Bundled Maven (no global install needed)
├── src/
│   ├── main/
│   │   ├── java/com/exam/allocation/
│   │   │   ├── RoomAllocationApplication.java
│   │   │   ├── config/
│   │   │   │   ├── DataSeeder.java          # admin account + departments + student logins
│   │   │   │   ├── PasswordConfig.java      # BCrypt encoder bean
│   │   │   │   └── SecurityConfig.java      # filter chain, roles, CSRF, logout
│   │   │   ├── filter/
│   │   │   │   └── JsonLoginFilter.java     # POST /api/auth/login + rate limiting
│   │   │   ├── controller/
│   │   │   │   ├── AdminDashboardController.java  # stats, audit, announcements, depts, subjects
│   │   │   │   ├── AllocationController.java      # students, rooms, allocate, publish, seats
│   │   │   │   ├── ApiExceptionHandler.java       # JSON error handling
│   │   │   │   ├── AuthController.java            # csrf, me, change-password
│   │   │   │   ├── BatchController.java           # course/batch management
│   │   │   │   ├── ChartController.java           # chart PDF download
│   │   │   │   ├── ExamController.java            # exam CRUD, lifecycle, conflicts, eligibility
│   │   │   │   ├── ImportController.java          # student / exam / room uploads
│   │   │   │   └── StudentSelfController.java     # student-scoped dashboard/exams/seat/profile
│   │   │   ├── model/
│   │   │   │   ├── Announcement.java  AuditLog.java  Batch.java
│   │   │   │   ├── Department.java    Exam.java      ExamEligibility.java
│   │   │   │   ├── ExamRoom.java      ExamSemester.java Room.java
│   │   │   │   ├── Seat.java          SeatAllocation.java Student.java
│   │   │   │   ├── Subject.java       User.java
│   │   │   ├── repository/                      # 14 Spring Data JPA interfaces
│   │   │   └── service/
│   │   │       ├── AllocationService.java       # seating algorithm + publish
│   │   │       ├── AuditService.java            # audit_logs writer
│   │   │       ├── BatchService.java            # seeds A–G, Add Course CRUD
│   │   │       ├── ChartPdfService.java         # PDF rendering (chart + roster)
│   │   │       ├── ConflictService.java         # clash / double-booking detection
│   │   │       ├── ExamImportService.java       # exam PDF/Excel/CSV import
│   │   │       ├── FileTableReader.java         # shared PDF/Excel/CSV → rows
│   │   │       ├── ImportService.java           # student PDF/Excel/CSV import
│   │   │       ├── RoomImportService.java       # room number/desk count import
│   │   │       ├── SessionService.java          # session payload inside a JPA session
│   │   │       └── UserAccountService.java      # BCrypt accounts, password reset/change
│   │   └── resources/
│   │       ├── application.properties
│   │       └── static/index.html (markup, CSS, core JS) + app.js (auth + enhanced portals)
│   └── test/java/com/exam/allocation/
│       ├── controller/ExamControllerTest.java
│       └── service/ConflictServiceTest.java, RoomImportServiceTest.java
├── target/                       # Maven build output (generated; never edited)
└── tools/test/                   # Test suites + fixtures
    ├── cdp-test.mjs              # admin/student UI end-to-end suite (40 checks)
    ├── responsive-test.mjs       # rooms/search/room picker + overflow sweep
    ├── responsible-test.mjs      # accessibility/responsiveness/auth UX suite
    ├── exam-api-smoke.py         # real-HTTP exam API smoke test (20 checks)
    ├── duplicate-seat-test.py    # verifies no seat is allocated twice
    ├── seed.sh                   # seeds baseline data via curl
    ├── probe.mjs                 # small CDP debugging helper
    ├── MakePdf.java / MakeExamPdf.java / ExtractPdf.java
    ├── make_xlsx.py / make_exam_xlsx.py
    └── test-students.{csv,xlsx,pdf}, test-exams.{csv,xlsx,pdf}
```

---

# 4. Important Folders

### `src/main/java`
Production code split by responsibility: `config` (security/seeding),
`filter` (login), `controller` (HTTP), `service` (business rules), `repository`
(JPA) and `model` (entities/tables).

### `src/main/resources`
`application.properties` (database, port, upload limits, admin seed) and
`static/` — the frontend. `index.html` contains the markup, all CSS and the core
tab logic (`api()`, `showSection()`, forms, allocation/PDF/import actions);
`app.js` adds the session/auth bootstrap, role routing, enhanced admin renders
and the student portal.

### `src/test`
JUnit 5 + Mockito unit tests (20 tests). They run without a database because the
repositories are mocked.

### `target`
Maven output; contains compiled classes and
`room-allocation-system-0.0.1-SNAPSHOT.jar`. **Never edit files inside
`target/`** — it is deleted by `mvn clean`.

### `tools/test`
End-to-end UI suites (CDP), Python HTTP smoke tests, the seed script, fixture
generators and upload fixtures. The UI/API suites need a running application.

### Package folders (`src/main/java/com/exam/allocation/…`)
| Folder | Purpose |
|---|---|
| `config` | `SecurityConfig`, `PasswordConfig`, `DataSeeder` |
| `filter` | `JsonLoginFilter` — handles the JSON login request before Spring's form login |
| `controller` | REST endpoints; translate HTTP ↔ service calls; no business rules |
| `service` | Allocation, imports, PDF, conflicts, batches, accounts, audit |
| `repository` | Spring Data JPA interfaces (SQL generated by Hibernate) |
| `model` | JPA `@Entity` classes = database tables |

Uploaded files are parsed in memory (`MultipartFile`) and never written to disk.
There is **no `templates` folder** (Thymeleaf is not used) and no frontend build
tooling.

---

# 5. Main Important Files

| File | Location | Purpose | Important Functions |
|---|---|---|---|
| `index.html` | `static/` | Login screen, admin shell (11 tabs), student portal markup, all CSS, core tab logic | `api()`, `showSection()`, `generateAllocation()`, `uploadImport()`, `uploadExamImport()`, `uploadRoomImport()`, `downloadChartPdf()` |
| `app.js` | `static/` | Session bootstrapping, role routing, CSRF-aware auth, enhanced admin renders, student portal | `boot()`, `doLogin()`, `route()`, `adminDashboard()`, `renderStudentsX()`, `publishAllocations()`, `bootStudent()` |
| `SecurityConfig.java` | `config/` | URL → role rules, CSRF cookie, session policy, logout, JSON 401/403 | `filterChain()`, `authenticationProvider()` |
| `JsonLoginFilter.java` | `filter/` | JSON login, friendly error messages, 5 attempts / 15 min limit | `doFilterInternal()`, `messageFor()` |
| `DataSeeder.java` | `config/` | Seeds the admin from config, departments, missing student accounts | `run()` |
| `UserAccountService.java` | `service/` | Account creation, status sync, reset/change password | `createStudentAccount()`, `changePassword()`, `resetStudentPassword()` |
| `SessionService.java` | `service/` | Returns the signed-in account/student as a plain map inside a transaction | `describe()`, `requireStudent()` |
| `AllocationService.java` | `service/` | Core seating algorithm, draft allocations, publish/unpublish | `generateAllocation()`, `fillDesk()`, `prepareSeats()`, `interleave()`, `matchesExam()`, `publishAllocations()` |
| `AllocationController.java` | `controller/` | Students/rooms/allocate/publish/seats endpoints | `getAllStudents()`, `addStudent()`, `generateAllocation()`, `publishAllocations()`, `getAllSeats()` |
| `ExamController.java` | `controller/` | Exam CRUD, status lifecycle, eligibility, conflicts | `addExam()`, `updateExam()`, `setExamStatus()`, `setEligibility()`, `getConflicts()` |
| `StudentSelfController.java` | `controller/` | Student-scoped dashboard, exams, seat, profile, password | `dashboard()`, `myExams()`, `mySeat()`, `updateProfile()` |
| `AdminDashboardController.java` | `controller/` | Live stats, audit trail, announcements, departments, subjects | `dashboard()`, `audit()`, `createAnnouncement()` |
| `ImportService.java` | `service/` | Student file import, header mapping, batch auto-registration, login creation | `importStudents()`, `mapHeader()` |
| `ExamImportService.java` | `service/` | Exam file import: extraction, validation, idempotent upsert, conflicts | `importExams()`, `parseDateValue()`, `parseTimeValue()` |
| `RoomImportService.java` | `service/` | Room number + desk count import, grid prediction | `importRooms()`, `predictGrid()` |
| `FileTableReader.java` | `service/` | Shared file → `List<String[]>` reader | `read()`, `readExcel()`, `readPdf()`, `readDelimited()` |
| `ConflictService.java` | `service/` | Scheduling clash + double-booking detection | `findConflicts()`, `isSameSlot()`, `allocationWarnings()` |
| `ChartPdfService.java` | `service/` | Builds the final PDF (header, legend, grid, roster, footers) | `buildChartPdf()`, `drawRoom()`, `drawSeat()`, `drawRoster()` |
| `BatchService.java` | `service/` | Course/batch management, seeds A–G | `run()`, `create()`, `ensureExists()`, `delete()` |
| `application.properties` | `resources/` | MySQL URL, port 8080, upload limits, admin seed, session timeout | — |
| `pom.xml` | root | Dependencies, Java 17, Mockito/Byte Buddy pins | — |
| `exam-api-smoke.py` | `tools/test/` | Real-HTTP exam API checks incl. lifecycle + auth | `call()`, `check()` |
| `cdp-test.mjs` | `tools/test/` | End-to-end UI test (40 checks) driving Chrome via CDP | `check()`, `evaluate()`, per-tab test blocks |

**Data flow of the two biggest services**
- `ExamImportService`: file bytes in → validated `Exam` rows + conflict messages out.
- `ChartPdfService`: `Exam` + `List<Seat>` in → `byte[]` PDF out.

---

# 6. Application Architecture

```text
User (admin or student)
   ↓ browser
Frontend  —  static/index.html + static/app.js  (fetch + CSRF header)
   ↓ HTTP / JSON / multipart
Security filter chain  —  session cookie, role rules, JsonLoginFilter, CSRF
   ↓
Controller layer  —  /api/**  (Spring @RestController)
   ↓
Service layer  —  allocation, imports, conflicts, PDF, batches, accounts, audit
   ↓
Repository layer  —  Spring Data JPA (interface + Hibernate)
   ↓
SQL Database  —  MySQL (default) / H2 / PostgreSQL
```

**Layer responsibilities**
- **Frontend**: login screen; admin SPA with tabbed sections; separate student
  portal; API helper adds `X-XSRF-TOKEN`; renders grids/tables; downloads PDFs.
- **Security**: `SecurityConfig` requires `ROLE_ADMIN` for every admin API and
  `ROLE_STUDENT` for `/api/student/**`; unauthenticated requests get JSON 401,
  wrong role gets JSON 403, mutations without CSRF get 403.
- **Controller**: request mapping, parameter binding, calling services.
- **Service**: all rules — audience matching, seat filling, file parsing,
  validation, conflicts, lifecycle side effects, PDF drawing, account handling.
- **Repository**: `JpaRepository` interfaces with derived queries.
- **Database**: persistent storage of all entities.

---

# 7. Database / SQL Connection

- **Database type**: MySQL by default; PostgreSQL and H2 drivers are bundled.
- **Configuration file**: `src/main/resources/application.properties`
- **Connection URL**: `jdbc:mysql://localhost:3306/room_allocation_db?createDatabaseIfNotExist=true`
- **Driver**: `com.mysql.cj.jdbc.Driver`
- **Username**: `root` — **Password**: `Akshay@6235` (local development defaults,
  matching `docker-compose.yml` and `.env.example`).
- **Overrides**: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`; Compose root password:
  `MYSQL_ROOT_PASSWORD`. Administrator: `APP_ADMIN_USERNAME` / `APP_ADMIN_PASSWORD`.
- **Workbench**: connect to `127.0.0.1:3306`, browse schema `room_allocation_db`
  (see `mysql-commands.md`).
- **ORM**: Spring Data JPA / Hibernate, dialect `MySQLDialect`
- **DDL**: `spring.jpa.hibernate.ddl-auto=update`; `auto_quote_keyword=true`
  because `row_number` is reserved on MySQL 8.
- **Sessions**: `server.servlet.session.timeout=30m`.

**Repositories** (all extend `JpaRepository<Entity, Long>` unless noted):

| Repository | Custom methods |
|---|---|
| `StudentRepository` | `findByRollNo`, `findByBatch`, `countByBatch` |
| `RoomRepository` | `findByRoomNumber` |
| `SeatRepository` | `findByRoomId`, `findByExamId`, `findByAllocatedStudentId` |
| `SeatAllocationRepository` | `findByExamId`, `findByExamIdAndStatus`, `findByExamIdAndStudentId`, `findByStudentId`, `findByStudentIdOrderByCreatedAtDesc`, `deleteByExamId`, `deleteByRoomId`, `deleteByStudentId` |
| `ExamEligibilityRepository` | `findByExamId`, `deleteByExamId` |
| `ExamSemesterRepository` | `findByExamId`, `deleteByExamId` |
| `ExamRoomRepository` | `deleteByExamId`, `deleteByRoomId` |
| `ExamRepository` | — |
| `BatchRepository` | `findByNameIgnoreCase`, `findByOrderByNameAsc` |
| `UserRepository` | `findByUsername` |
| `AnnouncementRepository` | `findAllByOrderByCreatedAtDesc` |
| `AuditLogRepository` | `findTop100ByOrderByTimestampDesc` |
| `DepartmentRepository` / `SubjectRepository` | `findByCodeIgnoreCase`, `findAllByOrderByCodeAsc` |

**Tables** are created by Hibernate from the entities: `student`, `users`,
`batch`, `exam`, `exam_eligibility`, `exam_semesters`, `room`, `seat`,
`seat_allocations`, `exam_rooms`, `announcements`, `audit_logs`, `departments`,
`subjects`. See §27.

---

# 8. Example SQL Code

Hibernate generates these tables automatically; equivalent hand-written SQL:

```sql
CREATE TABLE student (
    id             BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    roll_no        VARCHAR(255) UNIQUE,
    name           VARCHAR(255),
    branch         VARCHAR(255),
    department     VARCHAR(255),
    semester       INT,
    email          VARCHAR(255),
    phone          VARCHAR(255),
    account_status VARCHAR(255) NOT NULL,
    created_at     TIMESTAMP,
    batch          VARCHAR(255)
);

CREATE TABLE users (
    id                   BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    username             VARCHAR(255) NOT NULL UNIQUE,
    password             VARCHAR(255) NOT NULL,   -- BCrypt hash
    role                 VARCHAR(255) NOT NULL,   -- ADMIN | STUDENT
    enabled              BOOLEAN NOT NULL,
    must_change_password BOOLEAN NOT NULL,
    student_id           BIGINT REFERENCES student(id),
    created_at           TIMESTAMP
);

CREATE TABLE batch (
    id     BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    name   VARCHAR(255) NOT NULL UNIQUE,
    custom BOOLEAN NOT NULL
);

CREATE TABLE exam (
    id               BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    subject_name     VARCHAR(255),
    subject_code     VARCHAR(255),
    branch           VARCHAR(255),
    course           VARCHAR(255),
    exam_date        DATE,
    exam_time        TIME,
    end_time         TIME,
    semester         INT,
    exam_type        VARCHAR(255),
    instructions     VARCHAR(2000),
    status           VARCHAR(255) NOT NULL,
    duration_minutes INT,
    application_id   VARCHAR(255)
);

CREATE TABLE seat_allocations (
    id         BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    exam_id    BIGINT NOT NULL REFERENCES exam(id),
    student_id BIGINT NOT NULL REFERENCES student(id),
    room_id    BIGINT NOT NULL REFERENCES room(id),
    seat_id    BIGINT NOT NULL REFERENCES seat(id),
    status     VARCHAR(255) NOT NULL,             -- DRAFT | PUBLISHED
    created_at TIMESTAMP,
    UNIQUE (exam_id, student_id),
    UNIQUE (exam_id, seat_id)
);
```

```sql
-- INSERT
INSERT INTO student (roll_no, name, branch, batch) VALUES ('1', 'Akshay', 'CEC', 'E');
INSERT INTO batch (name, custom) VALUES ('H', TRUE);
-- a login account is created by the application with a BCrypt hash of the roll number
INSERT INTO users (username, password, role, enabled, must_change_password)
VALUES ('1', '$2a$10$...', 'STUDENT', TRUE, TRUE);

-- SELECT
SELECT * FROM student WHERE batch = 'E';
SELECT * FROM exam WHERE exam_date = DATE '2026-11-02';
SELECT * FROM seat_allocations WHERE exam_id = 1 AND status = 'PUBLISHED';

-- UPDATE
UPDATE student SET branch = 'MEC' WHERE roll_no = '1';
UPDATE exam SET status = 'PUBLISHED' WHERE id = 1;

-- DELETE
DELETE FROM student WHERE roll_no = '1';
DELETE FROM seat_allocations WHERE exam_id = 1;

-- JOIN: students seated in a given exam with their room
SELECT s.roll_no, s.name, s.branch, s.batch, r.room_number, st.row_number, st.column_number
FROM seat st
JOIN student s ON s.id = st.allocated_student_id
JOIN room     r ON r.id = st.room_id
WHERE st.exam_id = 1
ORDER BY r.room_number, st.row_number, st.column_number;
```

---

# 9. How Application Data Reaches SQL

```text
Administrator enters data (e.g. student form)
        ↓
app.js  →  POST /api/students  (+ X-XSRF-TOKEN, session cookie)
        ↓
SecurityConfig — /api/students requires ROLE_ADMIN
        ↓
AllocationController.addStudent()   — duplicate roll check, status/department defaults
        ↓
StudentRepository.save()            — Spring Data JPA
        ↓
UserAccountService.createStudentAccount() — BCrypt(roll number), mustChangePassword=true
        ↓
Hibernate INSERT into student + users (inside a transaction)
        ↓
SQL database (MySQL)
```

Reads flow the same way in reverse. A student session is confined to
`/api/student/**`; `StudentSelfController` resolves the caller through
`SessionService` and only returns that student's rows.

File uploads use the same path with `multipart/form-data`
(`POST /api/import/students`, `/api/import/exams`, `/api/import/rooms`), except the service parses and
validates the file before touching the repository.

---

# 10. Exam Data Upload

**Status: Implemented** (PDF, Excel, CSV).

### Endpoints & files
- `POST /api/import/exams` → `ImportController.importExams()` →
  `ExamImportService.importExams(file)` (admin only).
- Shared file parsing: `FileTableReader.read()` (`.pdf` via PDFBox,
  `.xlsx/.xls` via POI, `.csv/.txt/.tsv` via delimiter detection).
- UI: **Import Data → Exam Data** tab.

### Steps
1. **File validation** — empty file, missing file or unsupported extension
   (`.png`, …) → HTTP 400 with a readable message.
2. **Header detection** — the first five rows are scanned for subject, branch,
   batch/course, date, time and application-ID columns. If no header is found,
   the default order *Subject, Branch, Batch/Course, Date, Time* is used and a
   note is added to the result.
3. **Data extraction** — date and time cells are located **by content**
   (`parseDateValue`, `parseTimeValue`), so shifted PDF tables still parse.
   A column literally headed `course` is ambiguous: batch-like values (A–G or a
   registered batch) stay the batch target; other values (CEC, CSE) become the
   branch.
4. **Validation** (rows are rejected and reported, never stored half-broken):
   - missing subject → `invalid` + note
   - unparseable date (`99/99/2026`, empty) → `invalid` + note
   - unparseable time → exam stored **without** time + note
5. **Data storage** — `Exam` rows are saved as **DRAFT** (Hibernate default);
   an existing exam with the same subject+date is updated (`updated` counter),
   so re-imports are idempotent. Missing application IDs are auto-generated
   (`APP-<tag>-<yyyyMMdd>-<nnn>`). Batch-like course values are registered in
   the `batch` table.
6. **Reaches the timetable generator** — the Seating Chart tab reads
   `GET /api/exams`; `POST /api/allocate` seats eligible students.
7. **Conflicts** — after import, `ConflictService.findConflictsFor(new ids)`
   returns new same-slot clashes in `conflicts[]`, shown in a red panel.
8. **Error handling** — errors come back as `{"message": "..."}` (see §23).

Imported exams start as drafts and are **not** visible to students until an
administrator publishes the exam and its seating.

### Supported date/time formats
`yyyy-MM-dd`, `dd/MM/yyyy`, `dd-MM-yyyy`, `d.M.yyyy`, `dd MMM yyyy`,
`MMM d yyyy`, `yyyyMMdd`, Excel serial dates — times: `10:00`, `10:00 AM`,
`09.30`, `0930`, `9 pm`.

### Fixture proof
`tools/test/test-exams.{csv,xlsx,pdf}` contain a valid pair that clashes
(Mathematics & Physics on 05/12/2026 10:00) and one invalid row
(`99/99/2026`) — the import reports `3 new / 1 invalid / 1 conflict`.

---

# 11. Student Data

Core fields (no Division field anywhere):

```text
Roll Number: 1
Name:        Akshay
Branch:      CEC
Batch:       E
```

The entity also stores optional `department`, `semester`, `email`, `phone`,
`accountStatus` and `createdAt` for the student portal and admin dashboards.
Missing import cells are stored as `NULL`.

- **Entered**: Students tab form, inline edit rows, or a student-list upload.
- **Stored**: `student` table (`rollNo` unique, `name`, `branch`, `batch`,
  optional contact fields).
- **Login**: adding a student automatically creates a `users` row with the roll
  number as username **and** initial password (BCrypt hash,
  `mustChangePassword = true`). Disabling the student disables the account.
- **Retrieved**: `GET /api/students` (admin, supports `search`, `branch`,
  `batch`, `status`); students use `GET /api/student/me`.
- **Connected to exam/timetable data**: `AllocationService.matchesExam()`
  compares the exam's branch/batch (and eligibility rows) against the student;
  each `Seat` row references the student and exam, and `SeatAllocation` is the
  published record students read.
- **In the final PDF**: seat cells show Roll No, Name and `branch·batch`; the
  **STUDENT LIST** table shows Roll No | Name | Branch | Batch/Course | Room | Seat.

---

# 12. Course / Batch Management

**Status: Implemented.**

- **Defaults**: `A, B, C, D, E, F, G` — seeded by `BatchService` on startup
  (`ApplicationRunner`) whenever the `batch` table is empty, and lazily when
  `GET /api/batches` is called.
- **Add Course**: Courses tab → input + **Add Course** → `POST /api/batches
  {"name":"H"}` → stored as `custom = true`. Duplicates, blank names and names
  over 40 characters are rejected with HTTP 400.
- **Storage**: `batch` table (`id`, `name` unique, `custom` flag).
- **Use in the timetable system**:
  - the student form's Batch dropdown,
  - the exam form's Batch dropdown (`exam.course`) and Semester multi-select
    (`exam_semesters` rows, 1..8; legacy fallback `exam.semester`; no selection
    = every semester),
  - exam **eligibility** (`exam_eligibility` rows, assigned from the Exams tab),
  - student/exam imports auto-register unknown batch values (`ensureExists`),
  - `AllocationService.matchesExam()` matches eligibility rows first, then
    `exam.course` against `student.batch` (and the student's branch as a fallback).
- **Delete guard**: `DELETE /api/batches/{id}` is refused while any student still
  belongs to the batch (`countByBatch`).

---

# 13. Timetable Scheduling Logic

**Status: Implemented (allocation logic preserved from the original design).**

### Input data
`Exam` (branch + batch/course + optional explicit eligibility batches, date,
time), all **active** students, and the selected **active** rooms
(rows × columns × students-per-desk).

### Constraints
1. Only ACTIVE students whose branch/batch matches the exam are seated
   (blank / "All" = wildcard; explicit eligibility rows win).
2. A student is never seated twice in the same exam run.
3. Never more students than available seats (excess reported, not silently dropped).
4. Students of the same **branch·batch group** are spread apart (anti-copying).
5. At shared desks, students from *different* groups are preferred.
6. Inactive rooms are never used for a new chart.

### Scheduling algorithm — in plain language
> Put every eligible student into their branch·batch group, then take one
> student from each group in turn (round-robin) so the seating order mixes the
> groups. Walk the room desk by desk and fill each desk from the front of that
> order; when a desk seats several students, pick the next students from
> *different* groups whenever possible. Anything already seated for this exam is
> cleared first, so re-running simply regenerates the chart.

### Technically (`AllocationService.generateAllocation`)
1. Load the exam; keep only active rooms from the requested ids.
2. Delete the exam's previous `SeatAllocation` rows (a re-run replaces the chart).
3. Resolve eligibility: explicit `exam_eligibility` batch names, otherwise the
   legacy `exam.branch` / `exam.course` columns.
4. Filter ACTIVE students with `matchesExam`.
5. `groupingBy(groupKey)` → groups keyed by `branch·batch`
   → `interleave()` round-robins them alphabetically into one sequence.
6. Per room: `prepareSeats()` creates (or rebuilds) the seat grid
   `rows × columns × studentsPerDesk` and clears old assignments (idempotent).
7. Seats are consumed desk by desk; `fillDesk()` looks ahead in the queue and
   prefers a student whose group differs from the groups already at that desk;
   unused candidates return to the front of the queue in their original order.
8. Every seated student gets a **DRAFT** `SeatAllocation`
   (`UNIQUE(exam_id, student_id)` and `UNIQUE(exam_id, seat_id)` act as a safety net).
9. `ConflictService.allocationWarnings()` post-checks the result for students or
   rooms booked in a same-slot exam.
10. Returns `AllocationResult { success, message, eligibleStudents, allocated,
    unallocated, roomsUsed, conflicts[] }`.

### Publishing
- `POST /api/allocations/{examId}/publish` → all drafts become PUBLISHED;
  cancelled exams are refused.
- `POST /api/allocations/{examId}/unpublish` → back to DRAFT.
- Students only ever see PUBLISHED allocations; cancelling an exam drafts them.
- `GET /api/allocations?examId=&studentId=&status=` is the admin allocation view
  used by the Reports tab.

### Conflict checking
- `GET /api/exams/conflicts` — pairs of exams with the **same date + time** and
  an **overlapping audience** (blank/`all` filters are wildcards). Optional
  `?status=` filter on `GET /api/exams`.
- Allocation warnings — students seated in two same-slot exams (roll numbers
  listed) and rooms used by two same-slot exams.
- Reported after every `POST /api/allocate` in `conflicts[]` and in the UI
  (red panel under the chart / on the Exams tab).

### Output
Draft allocations + on-screen seat grid, JSON result, PDF (§14), and the
published student view (`GET /api/student/seat`).

```text
Exam Data ─┐
Student Data ─┼→ Eligibility filter → Group & interleave → Desk filling
Course/Batch Data ─┘                              ↓
                                        Draft allocations + conflict checks
                                                  ↓
                                     Publish → student portal + PDF
```

---

# 14. PDF Generation

**Status: Implemented.**

- **Library**: Apache **PDFBox** 2.0.31.
- **File**: `src/main/java/com/exam/allocation/service/ChartPdfService.java`.
- **Endpoint**: `GET /api/chart/pdf?examId=` (`ChartController`, admin only)
  returns the PDF with a `Content-Disposition` filename such as
  `seating-chart-E-Data-Structures-2026-11-02.pdf`.
- **How data arrives**: the controller loads the exam + its `Seat`s (each seat
  carries Student, Room, row/column/desk position) and calls
  `buildChartPdf(exam, seats)`.

### Layout
1. **Header** — "SEATING CHART", subject, `Branch / Batch / Course / Date /
   Time / Application ID`, generation timestamp.
2. **Legend** — every `branch·batch` group with its colour and seated count.
3. **One section per room** — landscape A4 grid (rows × columns); each cell is a
   desk. Single-student cells show Roll No (bold), Name and `branch·batch` on a
   group-coloured background; shared desks show one slot per position with a
   group-coloured bar. Vacant seats are marked `VACANT`. Rows break across pages
   with repeated column labels.
4. **STUDENT LIST** — a roster table:

   | Roll No | Name | Branch | Batch / Course | Room | Seat |
   |---|---|---|---|---|---|
   | 1 | Akshay | CEC | E | 121 | R1 C1 P1 |

   Sorted by group then numeric-aware roll number, with a repeated header row
   when it spans pages.
5. **Footer** — "Room Allocation System - generated <date>", page x of y.

### Simplified example of the actual code
```java
try (PDDocument document = new PDDocument()) {
    Cursor cursor = new Cursor(document);
    newPage(cursor);                       // landscape A4

    drawHeader(cursor, exam);
    Map<String, Integer[]> colors = courseColors(seats);
    drawLegend(cursor, colors);
    for (List<Seat> roomSeats : byRoom.values()) drawRoom(cursor, roomSeats, colors);
    drawRoster(cursor, exam, seats);

    cursor.cs.close();
    drawFooters(document);

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    document.save(out);
    return out.toByteArray();
}
```

---

# 15. Data Flow

```text
PDF / Excel / CSV input  (students, exams or rooms)
          ↓
    FileTableReader.read()        — PDFBox / POI / delimiter split
          ↓
    Header detection + column mapping
          ↓
    Row extraction + validation   — invalid rows rejected with reasons
          ↓
    SQL Database                  — student (+ user), exam, room, batch tables
          ↓
    Exam lifecycle + eligibility  — draft → published, batch assignment
          ↓
    Scheduling engine             — interleave + desk filling
          ↓
    Draft seat allocations        — seat + seat_allocations rows
          ↓
    Conflict checking             — same-slot clashes, double bookings
          ↓
    Publish                       — students see their seat
          ↓
    PDF generation                — ChartPdfService
          ↓
    Final PDF download            — /api/chart/pdf
```

Every stage is implemented; failures at extraction/validation stop that row
(with a message) but never corrupt existing data.

---

# 16. Important UI Screens

Authentication is required for every screen except the login page.

### Login
Username + password (JSON `POST /api/auth/login`). The development
administrator is `admin / admin`; a student signs in with their roll number as
both username and initial password.

### Administrator tabs
| Tab | Purpose | Main Actions | Important Backend/API |
|---|---|---|---|
| **Dashboard** | Live counters + recent activity | Jump to tabs | `GET /api/admin/dashboard` |
| **Students** | Manage student info | Add, search/filter, inline edit, disable, reset password, delete | `GET/POST /api/students`, `PUT/DELETE /api/students/{id}`, `POST /api/students/{id}/status`, `POST /api/students/{id}/reset-password` |
| **Rooms** | Manage exam rooms | Add (desk/rows/columns auto-calc), edit, availability **Select**, delete | `GET/POST /api/rooms`, `PUT/DELETE /api/rooms/{id}`, `POST /api/rooms/{id}/status` |
| **Courses** | Batch/course management | **Add Course**, delete unused batches | `GET/POST /api/batches`, `DELETE /api/batches/{id}` |
| **Exams** | Exam list, lifecycle, eligibility, conflicts | Create/edit/delete, publish/cancel, assign batches, view clashes | `GET/POST /api/exams`, `PUT/DELETE /api/exams/{id}`, `POST /api/exams/{id}/status`, `PUT /api/exams/{id}/eligibility`, `GET /api/exams/conflicts` |
| **Seating Chart** | Generate and review the timetable | Pick exam + rooms, generate, see stats/conflicts/unseated chips, publish/unpublish, download PDF, print | `POST /api/allocate`, `GET /api/seats?examId=`, `GET /api/chart/pdf?examId=`, `POST /api/allocations/{examId}/publish` and `/unpublish` |
| **Import Data** | Upload student lists, exam data and rooms | Mode switch (Student List / Exam Data / Rooms), upload, preview, template download | `POST /api/import/students`, `/api/import/exams`, `/api/import/rooms` |
| **Calendar** | Month view of scheduled exams | Navigate months, day agenda, exam details | `GET /api/exams` |
| **Reports** | Per-exam allocation status | Eligible/seated/unseated/chart state, print | `GET /api/allocations?examId=`, `GET /api/exams` |
| **Announcements** | Post messages to students | Create/delete, audience by batch | `GET/POST /api/admin/announcements`, `DELETE /api/admin/announcements/{id}` |
| **Settings** | Change the administrator password | Change password | `POST /api/auth/change-password` |

### Student portal
| Tab | Purpose | Backend/API |
|---|---|---|
| **Overview** | Counts, next exam, latest published seat, announcements | `GET /api/student/dashboard` |
| **My Exams** | Published/eligible exams with seat when published | `GET /api/student/exams` |
| **My Seat** | Room, seat code, timing, instructions | `GET /api/student/seat?examId=` |
| **Profile** | Contact details + password change | `GET /api/student/me`, `PUT /api/student/profile`, `POST /api/student/password` |

---

# 17. API Endpoints

All responses are JSON except the PDF download. Errors return
`{"message": "..."}` with 400/409 for validation/data problems, 401 when not
signed in, 403 for wrong role/CSRF, 429 for rate limiting.

**Auth:** `GET /api/auth/csrf` (token), `POST /api/auth/login`
`{"username","password"}`, `POST /api/auth/logout`, `GET /api/auth/me`,
`POST /api/auth/change-password` `{"currentPassword","newPassword"}`.

**Administrator endpoints** (`ROLE_ADMIN`):

| Method | Endpoint | Purpose |
|---|---|---|
| GET | `/api/students?search=&branch=&batch=&status=` | List/filter students |
| POST | `/api/students` | Add student + login account |
| PUT | `/api/students/{id}` | Update student |
| DELETE | `/api/students/{id}` | Delete student (clears seats + account) |
| POST | `/api/students/{id}/status` | `{"status":"ACTIVE"}` or `{"status":"INACTIVE"}` |
| POST | `/api/students/{id}/reset-password` | `{"password":"..."}` |
| GET | `/api/rooms?active=` | List rooms |
| POST | `/api/rooms` | Add room |
| PUT | `/api/rooms/{id}` | Update room |
| POST | `/api/rooms/{id}/status` | `{"active":true}` or `{"active":false}` |
| DELETE | `/api/rooms/{id}` | Delete room + its seats/allocations |
| GET | `/api/batches` | List batches (defaults first) |
| POST | `/api/batches` | **Add Course** `{"name":"H"}` |
| DELETE | `/api/batches/{id}` | Delete unused batch |
| GET | `/api/exams?status=` | List exams (by date) |
| GET | `/api/exams/{id}` | One exam |
| POST | `/api/exams` | Create exam (always DRAFT) |
| PUT | `/api/exams/{id}` | Update exam (published + schedule change → DRAFT) |
| POST | `/api/exams/{id}/status` | `{"status":"DRAFT"}`, `PUBLISHED`, `COMPLETED` or `CANCELLED` |
| PUT | `/api/exams/{id}/eligibility` | `{"batches":["A","B"]}` (empty = all) |
| GET | `/api/exams/{id}/eligibility` | List eligible batches |
| DELETE | `/api/exams/{id}` | Delete (published exams refused) |
| GET | `/api/exams/conflicts` | Detect same-slot clashes |
| POST | `/api/allocate` | Generate chart `{"examId":1,"roomIds":[1,2]}` |
| POST | `/api/allocations/{examId}/publish` | Publish seats to students |
| POST | `/api/allocations/{examId}/unpublish` | Hide seats again |
| GET | `/api/allocations?examId=&studentId=&status=` | Stored allocations (admin view) |
| GET | `/api/seats?examId=` | Seats of one exam (all if omitted) |
| GET | `/api/chart/pdf?examId=` | Download the chart PDF |
| POST | `/api/import/students` | Import student list (multipart `file`) |
| POST | `/api/import/exams` | Import exam data |
| POST | `/api/import/rooms` | Import room numbers + desk counts |
| GET | `/api/admin/dashboard` | Live statistics + recent activity |
| GET | `/api/admin/audit` | Last 100 audit entries |
| GET/POST/DELETE | `/api/admin/announcements[/{id}]` | Announcements |
| GET/POST/DELETE | `/api/admin/departments[/{id}]` | Departments (backend; not yet on a tab) |
| GET/POST/DELETE | `/api/admin/subjects[/{id}]` | Subjects (backend; not yet on a tab) |

**Student endpoints** (`ROLE_STUDENT`, always scoped to the caller):
`GET /api/student/me`, `/dashboard`, `/exams`, `/seat?examId=`;
`PUT /api/student/profile` `{"email","phone"}`;
`POST /api/student/password` `{"currentPassword","newPassword"}`.

**Validation** is performed in controllers/services: duplicate roll no / room no /
batch name, required subject, end time after start time, semester 1–8,
publish requires date + start time, delete guards, file type checks, and Spring's
`MultipartException` handling (max 20 MB per file).

---

# 18. Important Classes / Components

| Component | Type | Role & interaction |
|---|---|---|
| `SecurityConfig` | `@Configuration` | Filter chain: public shell/login, `/api/student/**` → STUDENT, other `/api/**` → ADMIN, CSRF cookie, JSON 401/403, logout |
| `JsonLoginFilter` | `OncePerRequestFilter` | Parses the JSON login body, authenticates, saves the session, rate-limits failures |
| `DataSeeder` | `ApplicationRunner` | Seeds admin from config, 4 departments, missing student accounts |
| `UserAccountService` | Service (`UserDetailsService`) | BCrypt accounts, create/sync/delete, reset/change password |
| `SessionService` | Service | Flattens the signed-in account inside a JPA session (avoids LazyInitializationException) |
| `AuditService` | Service | Writes `audit_logs` rows for sensitive actions |
| `AllocationController` | REST controller | Students/rooms/allocate/publish/seats |
| `ExamController` | REST controller | Exam CRUD, lifecycle, eligibility, conflicts |
| `StudentSelfController` | REST controller | Student dashboard/exams/seat/profile |
| `AdminDashboardController` | REST controller | Stats, audit, announcements, departments, subjects |
| `BatchController` | REST controller | Batch list/create/delete |
| `ImportController` | REST controller | Three upload endpoints |
| `ChartController` | REST controller | Loads exam + seats → `ChartPdfService.buildChartPdf()` |
| `ApiExceptionHandler` | `@RestControllerAdvice` | Maps `IllegalArgumentException`→400, `DataIntegrityViolationException`→409, multipart errors→400 |
| `AllocationService` | Service | Seating algorithm (§13), draft allocations, publish/unpublish |
| `ImportService` | Service | Student import: header mapping, token balancing, batch auto-registration, account creation |
| `ExamImportService` | Service | Exam import: extraction, validation, idempotent upsert, conflict reporting |
| `RoomImportService` | Service | Room import: room number + desk count, grid prediction |
| `FileTableReader` | `@Component` | Shared file → `List<String[]>` reader |
| `ConflictService` | Service | `isSameSlot()`, `findConflicts()`, `allocationWarnings()` |
| `BatchService` | Service (`ApplicationRunner`) | Seeds A–G; create/ensure/delete with usage guard |
| `ChartPdfService` | Service | Pure PDF drawing (grid, legend, roster, footers) |
| Entities | JPA | See §27 |
| `*Repository` | Spring Data | All SQL generated by Hibernate |

---

# 19. Configuration Files

| File | Controls | Safe to change? | Secrets? |
|---|---|---|---|
| `src/main/resources/application.properties` | DB URL/driver/user, Hibernate DDL, server port `8080`, multipart limits (20 MB / 25 MB), admin seed, session timeout | Yes | Local MySQL defaults are development-only; production credentials must come from environment variables |
| `.env.example` | Template for `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `MYSQL_ROOT_PASSWORD`, `APP_ADMIN_USERNAME`, `APP_ADMIN_PASSWORD` | Yes | Placeholder development values only |
| `pom.xml` | Dependencies, Java 17, Spring Boot 3.2.0, Mockito/Byte Buddy pins | Yes (keep versions consistent) | No |
| `docker-compose.yml` | Local MySQL 8 service, host port **3306**, database `room_allocation_db` | Yes — change the local root password before shared use | Local-dev password placeholder |
| `.vscode/settings.json` | Editor behaviour only | Yes | No |
| `tools/test/*` | Test endpoints/paths | Yes (ports must match the running instance) | No |

`.env` exists locally and is git-ignored. There is no `application.yml` and no
`package.json` (the frontend has no build tooling).

---

# 20. Build and Run Process

```text
1. Install prerequisites : JDK17+ (tests also run on JDK 25), Node18+
                           (only for the UI suites), Python3 (smoke tests/fixtures)
2. Configure database    : start local MySQL with docker compose up -d
3. Environment variables : optional overrides (see .env.example)
4. Install dependencies  : resolved automatically by Maven on first build
5. Build project          : mvn clean package -DskipTests
6. Start application      : java -jar target/room-allocation-system-0.0.1-SNAPSHOT.jar
7. Open application       : http://localhost:8080  → sign in as admin / admin
```

Exact commands (bundled Maven, no global install):

```bash
# unit tests (no database needed)
./.mvn-tool/apache-maven-3.9.9/bin/mvn test

# build
./.mvn-tool/apache-maven-3.9.9/bin/mvn clean package -DskipTests

# run (default port 8080, MySQL)
java -jar target/room-allocation-system-0.0.1-SNAPSHOT.jar

# run on another port (used by some test suites)
java -jar target/room-allocation-system-0.0.1-SNAPSHOT.jar --server.port=8081

# HTTP smoke test against a running server (defaults to :8080)
python tools/test/exam-api-smoke.py http://localhost:8081

# UI suites (seed + Chrome + node)
bash tools/test/seed.sh http://localhost:8081/api
node tools/test/cdp-test.mjs
```

---

# 21. Development vs Production

| Aspect | Development | Production |
|---|---|---|
| Database | MySQL via `docker-compose.yml`; persistent Docker volume | MySQL/PostgreSQL via a managed or self-hosted server; set `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` |
| Credentials | Local defaults in Compose/application config; `.env` optional | Environment variables/secrets; never commit production secrets |
| Admin account | Seeded `admin / admin` | Set `APP_ADMIN_USERNAME` / `APP_ADMIN_PASSWORD` before first start and change the password in Settings |
| Build | `mvn package` on demand, `show-sql=true` for debugging | `mvn clean package`, set `spring.jpa.show-sql=false` |
| Logging | SQL printed to console | Reduce logging; send logs/audit to a log store |
| Port | `8080`/`8081` behind no proxy | Reverse proxy with TLS |
| File storage | Uploads parsed in memory, never persisted | Same — add virus scanning/quota if exposed publicly |
| Security | Dev credentials, HTTP | HTTPS, strong admin password, environment secrets, restricted network |

---

# 22. Security

- **Authentication**: implemented with Spring Security (`spring-boot-starter-security`).
  `POST /api/auth/login` is handled by `JsonLoginFilter`, which reads a JSON body,
  authenticates against `users`, saves the `SecurityContext` in the HTTP session
  and returns the account payload. There is no form login or HTTP Basic.
- **Passwords**: BCrypt hashes (`PasswordConfig`). Administrators are seeded from
  configuration; each student gets an account with the roll number as the initial
  password and `mustChangePassword = true`. Self-service change requires the
  current password and a new password of at least 6 characters; admin reset
  requires at least 4 characters.
- **Authorization**: `ROLE_ADMIN` for all management APIs, `ROLE_STUDENT` for
  `/api/student/**`. Student handlers resolve the caller via `SessionService`, so
  a student can only read/update their own record. Unauthenticated → JSON 401;
  wrong role → JSON 403.
- **Sessions & CSRF**: session cookies (`JSESSIONID`) with a 30-minute idle
  timeout; CSRF tokens in a readable `XSRF-TOKEN` cookie echoed in the
  `X-XSRF-TOKEN` header (the SPA's `api()` helper does this). Login is exempt
  from the CSRF check; logout deletes both cookies.
- **Rate limiting**: 5 failed logins for the same IP + username in 15 minutes →
  HTTP 429.
- **Audit trail**: `audit_logs` records logins/seeding, student/room/exam
  changes, allocation generation/publishing, password changes and announcements.
- **Database credentials**: local defaults are development-only; production
  credentials must come from environment variables.
- **File uploads**: only `.pdf/.xlsx/.xls/.csv/.txt/.tsv` accepted, parsed in
  memory, never written to disk; 20 MB per file / 25 MB per request.
- **Injection**: Spring Data JPA/Hibernate parameter binding for every query;
  no string-built SQL.
- **Sensitive data**: password hashes are never serialized (the `User` entity is
  not returned by the API); uploads contain student data only.

---

# 23. Error Handling

| Where | What | Behaviour |
|---|---|---|
| `SecurityConfig` | No session | HTTP 401 `{"message":"Please sign in to continue."}` |
| `SecurityConfig` | Wrong role / missing CSRF | HTTP 403 `{"message":"You do not have permission…"}` |
| `JsonLoginFilter` | Missing fields / unknown user / bad password / disabled | HTTP 401 with a specific message |
| `JsonLoginFilter` | 5 failures in 15 min | HTTP 429 "Too many failed attempts…" |
| `ApiExceptionHandler` | `IllegalArgumentException` | HTTP 400 `{"message": "..."}` → UI toast |
| `ApiExceptionHandler` | `DataIntegrityViolationException` | HTTP 409 duplicate/invalid data |
| `ApiExceptionHandler` | Missing/oversized multipart file | HTTP 400 "A file is required…" |
| Import services | Unsupported extension / empty file | HTTP 400 with the supported list |
| `ExamImportService` | Row without subject or with bad date | Row rejected, counted in `invalid`, reason in `notes` |
| Exam import | Unparseable time | Exam stored without a time + note |
| `ExamController` | Missing subject, end ≤ start, semester outside 1–8, unknown status, publish without date/time, delete published exam | HTTP 400 with a readable message |
| `AllocationService` | No matching students / no active rooms / cancelled exam publish | `success=false` or HTTP 400 with a readable message |
| `ConflictService` | Same-slot exams, double-booked students/rooms | Warnings in `conflicts[]` — allocation still succeeds |
| `BatchService` | Deleting a batch in use | HTTP 400 "Cannot delete … N student(s) still belong…" |
| `ChartPdfService` | No seats for the exam | HTTP 400 "No seating chart exists … Generate the allocation first." |
| `UserAccountService` | Wrong current password / short new password | HTTP 400 with a readable message |
| Frontend `api()` | Any non-2xx | Shows a red toast; a 401 returns to the login screen; page never crashes |

**Troubleshooting examples**
- *"No students match this exam (batch/course: E, branch: CEC)"* → fix the exam
  target/eligibility or the student data.
- *"N student(s) could not be seated"* → add rooms / rows / columns or select more rooms.
- *"Unsupported file type"* → convert the file to `.pdf`, `.xlsx` or `.csv`.
- *"Too many failed attempts"* → wait 15 minutes or restart to clear the
  in-memory limiter (development only).

---

# 24. Testing

**Status: Implemented.** Unit tests live in `src/test`; end-to-end suites and
fixtures live in `tools/test/`.

| Test | File | Covers |
|---|---|---|
| Unit tests — **20 tests** | `ExamControllerTest` (12), `ConflictServiceTest` (7), `RoomImportServiceTest` (1) | Exam validation/lifecycle/publish/delete guards/status filter, conflict rules and wildcards, room import + grid prediction |
| Exam API smoke (20 checks) | `tools/test/exam-api-smoke.py` | Sign-in + CSRF, validation, draft/publish/cancel lifecycle, delete guard, eligibility round-trip over real HTTP/MySQL |
| UI end-to-end (40 checks) | `tools/test/cdp-test.mjs` | Admin login, dashboard stats, student CRUD, room math, batches, exam creation, allocation + shared desks + legend, student/exam imports, clash reporting, PDF, no page errors |
| Rooms + responsiveness | `tools/test/responsive-test.mjs` | Room name/search/Select, allocation room picker, zero page-level horizontal overflow at 1920/1366/1024/768/390/320 px |
| Responsible UI suite (17 checks) | `tools/test/responsible-test.mjs` | Accessibility/labels, responsive layout, auth UX |
| Double-booking check | `tools/test/duplicate-seat-test.py` | Self-contained: creates its own students/exam/room, verifies no shared seat, no duplicate student, capacity respected, then deletes everything it created |
| Data seeding | `tools/test/seed.sh` | Signs in as admin, sends CSRF, resets data, posts students/rooms/exam, generates the baseline chart and imports fixtures |
| Fixture generators | `make_xlsx.py`, `make_exam_xlsx.py`, `MakePdf.java`, `MakeExamPdf.java` | Build the `.xlsx`/`.pdf` upload fixtures |
| PDF extraction debug | `ExtractPdf.java` | Prints exactly what the importer sees |

Run:
```bash
# unit tests (verified: 20 tests, 0 failures)
./.mvn-tool/apache-maven-3.9.9/bin/mvn test

# terminal 1 — app
java -jar target/room-allocation-system-0.0.1-SNAPSHOT.jar --server.port=8081

# terminal 2 — smoke test
python tools/test/exam-api-smoke.py http://localhost:8081

# UI suite: seed, start headless Chrome, run the checks
bash tools/test/seed.sh http://localhost:8081/api
chrome.exe --headless=new --remote-debugging-port=9333 --user-data-dir=%TEMP%/chrome-test http://localhost:8081/
node tools/test/cdp-test.mjs
```

Latest verified results (2026-10-18, freshly built jar on an isolated H2 database):
`mvn test` = **20 tests, 0 failures**; `exam-api-smoke.py` = **ALL EXAM API
CHECKS PASSED** (20/20); `duplicate-seat-test.py` = **PASS**;
`cdp-test.mjs` = **ALL UI TESTS PASSED** (40 checks, no page errors);
`responsive-test.mjs` = **ALL RESPONSIVE TESTS PASSED** (57 checks, no page
errors); `responsible-test.mjs` = **ALL RESPONSIBLE-MD CHECKS PASSED**.

---

# 25. Common Developer Tasks

| Task | Files to change |
|---|---|
| Add a new batch by default | `BatchService.DEFAULT_BATCHES` |
| Change seeded departments | `DataSeeder.run()` |
| Add a new student field | `model/Student.java`, `AllocationController` (add/update), `ImportService` (header alias + extraction), `index.html` form/table, `app.js` payload, optionally `ChartPdfService` roster |
| Add a new exam field | `model/Exam.java`, `ExamController` (create/update/validate), `ExamImportService` (alias + extraction), `index.html`/`app.js` exams tab, `ChartPdfService.drawHeader()` |
| Change exam lifecycle rules | `ExamController.addExam/updateExam/setExamStatus/deleteExam` |
| Change eligibility rules | `ExamController.setEligibility()` + `AllocationService.matchesExam()` |
| Change timetable rules | `AllocationService` — `matchesExam()`, `interleave()`, `fillDesk()` |
| Change seat publishing | `AllocationService.publishAllocations/unpublishAllocations` |
| Change PDF layout | `ChartPdfService` (grid: `drawRoom/drawSeat`; roster: `drawRoster`; header: `drawHeader`) |
| Add a new API endpoint | New/existing `controller/*.java`; add the path to `SecurityConfig`; register errors in `ApiExceptionHandler` |
| Change login rules / messages | `filter/JsonLoginFilter.java` + `config/SecurityConfig.java` |
| Change password policy | `UserAccountService.changePassword/resetStudentPassword` |
| Add an audit action | Inject `AuditService` and call `record(...)` |
| Add a database field | Entity → restart (Hibernate `ddl-auto=update` alters the table) → repositories/controllers/UI → update §8/§27 |
| Change DB configuration | `src/main/resources/application.properties` (+ `database.md`) |
| Add a new UI tab | `index.html` (tab button + `section-view`), `app.js` (`showSection`, loader/render function) |
| Modify conflict rules | `ConflictService` (`isSameSlot`, `audiencesOverlap`, `allocationWarnings`) |
| Regenerate fixtures | `python make_xlsx.py`, `python make_exam_xlsx.py`, `java MakePdf.java test-students.pdf`, `java MakeExamPdf.java test-exams.pdf` |

---

# 26. Example Complete Workflow

```text
Administrator adds a student:
Roll No: 1
Name: Akshay
Branch: CEC
Batch: E
```

```text
Student form (Students tab)
     ↓  POST /api/students {"rollNo":"1","name":"Akshay","branch":"CEC","batch":"E"}
SecurityConfig (ROLE_ADMIN) + unique roll-number check
     ↓  StudentRepository.save()  +  UserAccountService.createStudentAccount()
Database:  INSERT INTO student … ; INSERT INTO users (username='1', password=BCrypt('1'), …)
     ↓
Exam "Data Structures" created (DRAFT) → eligibility batch E → status PUBLISHED
     ↓
Seating Chart tab → select exam + rooms → POST /api/allocate {"examId":1,"roomIds":[1]}
matchesExam(): eligibility/course E == student.batch E → eligible
     ↓  interleave + fillDesk → Seat + SeatAllocation (DRAFT)
     ↓
Admin reviews the grid/conflicts → POST /api/allocations/1/publish
     ↓
Student signs in (1 / 1, prompted to change the initial password) → My Seat
     ↓  GET /api/student/seat?examId=1
Room 121, seat R1C2P1 + instructions
     ↓  GET /api/chart/pdf?examId=1 (admin)
Final PDF
```

Expected final PDF content for this student:

```text
SEATING CHART
Data Structures
Branch: All   Batch/Course: E   Date: 2026-11-02   Time: 10:00   Application ID: ...

[desk cell] 1 / Akshay / CEC·E     (coloured by group)

STUDENT LIST
Roll No | Name   | Branch | Batch / Course | Room | Seat
1       | Akshay | CEC    | E              | 121  | R1 C2 P1
```

---

# 27. Database Relationship Diagram

```text
STUDENT                          USER (users)                  EXAM
---------                        ------------                  ---------
id (PK)                          id (PK)                       id (PK)
roll_no (UNIQUE)                 username (UNIQUE)             subject_name
name                             password (BCrypt)             subject_code
branch                           role (ADMIN|STUDENT)          branch
department                       enabled                       course
semester                         must_change_password          exam_date
email                            student_id (FK → student.id)  exam_time
phone                            created_at                    end_time
account_status                                                 semester
created_at                                                     exam_type
batch (soft reference)                                         instructions
                                                               status (DRAFT|PUBLISHED|COMPLETED|CANCELLED)
                                                               duration_minutes
                                                               application_id

BATCH                            EXAM_ELIGIBILITY              EXAM_ROOM
------                           ----------------              ---------
id (PK)                          id (PK)                       id (PK)
name (UNIQUE)                    exam_id (FK → exam.id)        exam_id (FK → exam.id)
custom                           batch_name                    room_id (FK → room.id)
                                 UNIQUE(exam_id, batch_name)   UNIQUE(exam_id, room_id)

ROOM                             SEAT                          SEAT_ALLOCATIONS
---------                        --------                      ----------------
id (PK)                          id (PK)                       id (PK)
room_number (UNIQUE)             row_number                    exam_id (FK → exam.id)
name                             column_number                 student_id (FK → student.id)
capacity                         desk_position                 room_id (FK → room.id)
rows_count                       room_id (FK → room.id)        seat_id (FK → seat.id)
columns_count                    allocated_student_id (FK)     status (DRAFT|PUBLISHED)
students_per_desk                exam_id (FK → exam.id)        created_at
building                                                       UNIQUE(exam_id, student_id)
floor                                                          UNIQUE(exam_id, seat_id)
active

ANNOUNCEMENTS  id, title, body, batch_name, created_at
AUDIT_LOGS     id, actor, action, target, details, timestamp
DEPARTMENTS    id, code (UNIQUE), name
SUBJECTS       id, code (UNIQUE), name, department
```

Relationships:
- `User → Student` **many-to-one** (one login per student; null for admins).
- `Seat → Student` **many-to-one** (a student sits in many exams over time).
- `Seat → Room` **many-to-one**; `Room → Seat` **one-to-many** (cascade delete).
- `Seat → Exam` **many-to-one**; deleting an exam removes its seats.
- `SeatAllocation → Exam/Student/Room/Seat` **many-to-one**, with uniqueness
  constraints enforcing one seat per student and one student per seat **per exam**.
- `ExamEligibility → Exam` many-to-one (explicit batch audience).
- `ExamRoom → Exam/Room` many-to-one (rooms remembered for a generated chart).
- `Student.batch` and `Exam.course` store **batch name strings** (no FK) — batches
  are soft-referenced so imported/unknown values never fail to save; `BatchService`
  registers them.

---

# 28. File/Folder Quick Reference

| Folder/File | What it is | When to modify it |
|---|---|---|
| `src/main/java/.../config/` | Security chain, password encoder, seeding | Auth/seeding changes |
| `src/main/java/.../filter/` | JSON login filter + rate limiter | Login behaviour |
| `src/main/java/.../controller/` | REST API endpoints | Adding/changing endpoints |
| `src/main/java/.../service/AllocationService.java` | Seating algorithm + publishing | Timetable rules |
| `src/main/java/.../service/ExamImportService.java` | Exam upload parsing/validation | Exam-file formats |
| `src/main/java/.../service/ImportService.java` | Student upload parsing | Student-file formats |
| `src/main/java/.../service/RoomImportService.java` | Room upload parsing | Room-file formats |
| `src/main/java/.../service/FileTableReader.java` | Shared PDF/Excel/CSV reader | File-type support |
| `src/main/java/.../service/ChartPdfService.java` | Final PDF rendering | PDF layout/content |
| `src/main/java/.../service/ConflictService.java` | Clash/double-booking rules | Conflict rules |
| `src/main/java/.../service/BatchService.java` | Batch seeding + CRUD rules | Default batches |
| `src/main/java/.../service/UserAccountService.java` | Accounts/passwords | Login policy |
| `src/main/java/.../model/` | Entities = DB tables | Schema changes |
| `src/main/java/.../repository/` | JPA queries | New queries |
| `src/main/resources/application.properties` | DB, port, upload limits, admin seed | Environment configuration |
| `src/main/resources/static/index.html` | Markup + CSS + core tab logic (login/admin/student) | Markup/style/core UI changes |
| `src/main/resources/static/app.js` | Auth bootstrap, role routing, enhanced renders, student portal | Session/portal behaviour |
| `src/test/java/...` | JUnit unit tests | New unit tests |
| `pom.xml` | Dependencies & build | Adding libraries |
| `docker-compose.yml` | Local MySQL | Production-like database |
| `tools/test/` | UI/API tests, fixtures, seed script | Test scenarios/fixtures |
| `README.md` | Quick start | Run instructions change |
| `PROJECT_DETAILS.md` | This document | Any architecture change |
| `database.md` / `mysql-commands.md` | Database guide / SQL snippets | DB configuration changes |
| `todo.md` | Requirement status | When requirements are implemented |
| `target/` | Build output | Never edit |
| `plan.md` | Original project plan | Historical reference |

---

# 29. MBA / Project Presentation Understanding

**Business problem.** Colleges schedule dozens of exams; manual seating charts
take hours of staff time, are error-prone (duplicate students, wrong rooms,
clashing papers) and are hard to revise when a batch or room changes. Students
often do not know their seat until the day of the exam.

**Proposed solution.** A centralized web application where staff enter or upload
students and exam schedules, press one button, review a conflict-checked seating
timetable, publish it to a student portal and print a professional PDF.

**Target users.** Exam coordinators, department admins, examination office staff
and students (a browser is enough — no training or installation).

**Main workflow.**
1. Student data in (form or file upload) → 2. Rooms & batches defined →
3. Exam schedule uploaded (PDF/Excel) → 4. One click generates the seating chart →
5. Conflicts highlighted and resolved → 6. Publish → 7. PDF downloaded/printed.

**Operational benefits.**
- Seating work that took hours is done in seconds and can be re-run any time.
- Consistency: one source of truth for students, batches, exams and seats.
- Error reduction: duplicate roll numbers, invalid dates and scheduling clashes
  are detected automatically instead of on exam day.
- Auditability: every student's room and seat is recorded, published and printable.

**Automation benefits.** Round-robin spreading and desk-level mixing automate
the anti-copying rules that staff previously applied by hand; conflict checks
run on every generation instead of relying on memory.

**Data management.** Structured tables (students, users, batches, exams, rooms,
seats, allocations, eligibility) replace spreadsheets and paper; uploads are
validated before anything is stored.

**Scalability.** The same code runs against H2 for demos and MySQL/PostgreSQL
(`docker-compose.yml` provided) in production; rooms and students scale with the
database, and the PDF paginates automatically.

**Efficiency improvements.** Seconds vs. hours per exam; re-generation after
last-minute changes is a single click; publishing replaces notice boards.

**Error reduction.** Validation at every entry point (uniqueness, required
fields, date parsing, time ordering) + explicit scheduling-conflict reporting.

**Future possibilities (Planned / Not Implemented).** Multiple exam sessions per
day, hall-wise invigilator assignment, drag-and-drop seat adjustments,
Excel export of the roster, email/SMS notifications, and richer analytics.

---

# 30. Project Summary

- **What is this project?** A Spring Boot + Spring Security web application that
  manages students, rooms, batches and exams, generates exam seating timetables,
  publishes them to students and exports them as PDFs.
- **What problem does it solve?** Manual, slow and error-prone exam seating and
  timetable planning — including scheduling clashes, stale charts and missing
  student details.
- **How does data enter the system?** Through the browser UI (forms and tabs) or
  by uploading PDF/Excel/CSV files for students, exam schedules and rooms.
- **Where is the data stored?** In a SQL database via Spring Data JPA — MySQL
  by default, with H2/PostgreSQL available through configuration.
- **Who can access it?** Administrators (full management) and students (their
  own exams, seat and profile), enforced by Spring Security and CSRF protection.
- **How is the timetable generated?** `AllocationService` filters ACTIVE
  students by eligibility/branch/batch, interleaves them round-robin across
  `branch·batch` groups, fills each room desk preferring different groups, stores
  DRAFT seat allocations and runs conflict checks; an admin then publishes them.
- **How is it converted to PDF?** `ChartPdfService` (Apache PDFBox) draws a
  landscape A4 colour-coded seating grid plus the **Student List** table
  (Roll No | Name | Branch | Batch/Course | Room | Seat), served by
  `GET /api/chart/pdf?examId=`.
- **Most important files?** `SecurityConfig.java`, `JsonLoginFilter.java`,
  `AllocationService.java`, `ChartPdfService.java`, `ExamImportService.java`,
  `ConflictService.java`, `static/index.html`, `static/app.js`.
- **Most important folders?** `src/main/java/com/exam/allocation/{config,filter,service,controller,model,repository}`
  and `src/main/resources/static`.
- **How does the whole system work?** Files/forms → secured REST API → services
  → JPA repositories → SQL database → allocation + conflict services → admin
  publish → student portal + on-screen chart → PDF download.

---

*Keep this document updated whenever the architecture or major features change.*
