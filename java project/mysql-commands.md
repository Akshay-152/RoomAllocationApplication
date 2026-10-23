# MySQL Commands — Showing Data

All examples run against the local database used by the Room Allocation System:

```properties
host     : localhost (127.0.0.1)
port     : 3306
database : room_allocation_db
username : root
password : Akshay@6235
```

---

## 1. Connect

### Command line

```bash
mysql -h 127.0.0.1 -P 3306 -u root -p
# password prompt: Akshay@6235
```

Or in one line (visible in shell history — avoid on shared machines):

```bash
mysql -h 127.0.0.1 -P 3306 -u root -p"Akshay@6235"
```

> **Windows note:** `mysql` is often not on `PATH`. Use the full path instead:
>
> ```powershell
> & "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" -h 127.0.0.1 -u root -p
> ```
>
> The same is true for `mysqldump` in section 7 (`...\bin\mysqldump.exe`).

Once connected, switch to the schema:

```sql
USE room_allocation_db;
SHOW TABLES;
```

### MySQL Workbench

| Setting  | Value          |
|----------|----------------|
| Hostname | `127.0.0.1`    |
| Port     | `3306`         |
| Username | `root`         |
| Password | `Akshay@6235`  |
| Schema   | `room_allocation_db` |

---

## 2. Show the structure (metadata)

```sql
-- All databases the user can see
SHOW DATABASES;

-- All tables in the current database
SHOW TABLES;

-- Columns, types, keys of one table
DESCRIBE student;
SHOW COLUMNS FROM student;

-- Table create statement (exact DDL Hibernate generated)
SHOW CREATE TABLE student;

-- Indexes of a table
SHOW INDEX FROM seat;

-- Rows per table (InnoDB estimates)
SELECT table_name, table_rows
FROM information_schema.tables
WHERE table_schema = 'room_allocation_db';
```

---

## 3. Show all data

```sql
-- Entire table
SELECT * FROM student;
SELECT * FROM batch;
SELECT * FROM room;
SELECT * FROM exam;
SELECT * FROM seat;

-- Only some rows (limit the result set for big tables)
SELECT * FROM student LIMIT 50;

-- Only specific columns
SELECT roll_no, name, branch FROM student;
```

---

## 4. Filter, sort, group

```sql
-- Filter with WHERE
SELECT * FROM student WHERE branch = 'CSE';
SELECT * FROM student WHERE batch = 'A' AND branch = 'CSE';

-- Pattern matching
SELECT * FROM student WHERE name LIKE 'a%';

-- Sorting
SELECT * FROM exam ORDER BY exam_date ASC, exam_time ASC;

-- Counts
SELECT COUNT(*) FROM student;
SELECT COUNT(*) AS total_seats FROM seat;

-- Grouping: students per batch
SELECT batch, COUNT(*) AS students
FROM student
GROUP BY batch
ORDER BY batch;

-- Grouping: seats per room
SELECT room_id, COUNT(*) AS seats,
       SUM(CASE WHEN allocated_student_id IS NOT NULL THEN 1 ELSE 0 END) AS occupied
FROM seat
GROUP BY room_id;
```

---

## 5. Joins (combined views)

```sql
-- Seat -> Student -> Room: the actual seating plan
SELECT r.room_number,
       s.row_number,
       s.column_number,
       s.desk_position,
       st.roll_no,
       st.name,
       st.branch,
       st.batch
FROM seat s
JOIN student st ON st.id = s.allocated_student_id
JOIN room r     ON r.id = s.room_id
ORDER BY r.room_number, s.row_number, s.column_number, s.desk_position;

-- Exam -> allocated seats (invigilation / attendance sheet)
SELECT e.subject_name,
       e.branch,
       e.course,
       e.exam_date,
       e.exam_time,
       st.roll_no,
       st.name
FROM exam e
LEFT JOIN seat se    ON se.exam_id = e.id
LEFT JOIN student st ON st.id = se.allocated_student_id
ORDER BY e.exam_date, e.exam_time, st.roll_no;
```

Actual columns per table, for reference:

| Table | Columns |
|---|---|
| `student` | `id`, `roll_no` (unique), `name`, `branch`, `batch` |
| `room` | `id`, `room_number` (unique), `room_type`, `capacity`, `rows_count`, `columns_count`, `students_per_desk` |
| `seat` | `id`, `row_number`, `column_number`, `desk_position`, `room_id`, `allocated_student_id`, `exam_id` |
| `exam` | `id`, `subject_name`, `branch`, `course`, `exam_date`, `exam_time`, `application_id` |
| `batch` | `id`, `name` (unique), `custom` |
| `admin` | `id`, `username` (unique), ... |

> The column list above is the real schema Hibernate generates: `room_number`,
> `allocated_student_id`, `exam_id`, `row_number`, ... If a query reports an
> unknown column, run `DESCRIBE <table>;` or `SHOW CREATE TABLE <table>;` first.

---

## 6. Show data for one record

```sql
-- By primary key
SELECT * FROM student WHERE id = 1;

-- By business key
SELECT * FROM student WHERE roll_no = '21CS001';
SELECT * FROM room    WHERE room_number = 'R-101';

-- Everything related to one entity
SELECT * FROM seat WHERE allocated_student_id = 1;
SELECT * FROM seat WHERE exam_id = 3;
```

---

## 7. Export / view outside the shell

```bash
# Dump a query to CSV
mysql -h 127.0.0.1 -u root -p room_allocation_db \
  -e "SELECT roll_no, name, branch, batch FROM student" \
  --batch --raw > students.csv

# Readable table output
mysql -h 127.0.0.1 -u root -p room_allocation_db \
  -e "SELECT * FROM exam" --table

# Full logical backup of the schema + data
mysqldump -h 127.0.0.1 -u root -p room_allocation_db > room_allocation_db.sql
```

Inside the shell you can also change the output format:

```sql
-- aligned boxes instead of tab separated
\T

-- back to default
\t

-- show the statement taken by the client
pager cat
```

---

## 8. Handy session commands (`mysql` client)

| Command   | Meaning                                  |
|-----------|------------------------------------------|
| `\s`      | server/version/status info               |
| `\G`      | end a query with a vertical result (e.g. `SELECT * FROM exam\G`) |
| `\h`      | help                                     |
| `\q`      | quit                                     |
| `SELECT NOW();` | server time / connectivity check   |

---

## Troubleshooting

```sql
-- Who am I, which db am I in?
SELECT CURRENT_USER(), DATABASE(), VERSION();

-- Active connections
SHOW PROCESSLIST;
```

* **`Access denied for user 'root'@'localhost'`** — the running server still has
  an older root password because `mysql-data` volume already existed. Recreate it:
  `docker compose down -v && docker compose up -d` (deletes the local data volume).
* **`Can't connect ... port 3306`** — start MySQL: `docker compose up -d`.
* Passwords here are **local development only**. For anything shared or
  production, pass `DB_USERNAME` / `DB_PASSWORD` (and `MYSQL_ROOT_PASSWORD`)
  as environment variables instead of committing real secrets.