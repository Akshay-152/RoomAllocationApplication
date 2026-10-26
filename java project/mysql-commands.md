# MySQL Command Guide — Room Allocation System

A practical, copy-ready reference for inspecting and querying the local MySQL database used by the Room Allocation System.

> **Database:** `room_allocation_db`  
> **Host:** `127.0.0.1` (`localhost`)  
> **Port:** `3306`  
> **Username:** `root`  
> **Schema tables observed:** `campaign_deliveries`, `departments`, `exam`, `exam_eligibility`, `exam_rooms`, `exam_semesters`, `notification_campaigns`, `notification_templates`, `notifications`, `push_subscriptions`, `room`, `seat`, `seat_allocations`, `student`, `subjects`, `users`

## Contents

1. [Connect to MySQL](#1-connect-to-mysql)
2. [Select the database and list tables](#2-select-the-database-and-list-tables)
3. [Inspect table structures](#3-inspect-table-structures)
4. [Display data](#4-display-data)
5. [Filter, sort, and search](#5-filter-sort-and-search)
6. [Count and summarize records](#6-count-and-summarize-records)
7. [Join related tables](#7-join-related-tables)
8. [Inspect one record and relationships](#8-inspect-one-record-and-relationships)
9. [Export results and back up the database](#9-export-results-and-back-up-the-database)
10. [MySQL Workbench tips](#10-mysql-workbench-tips)
11. [Troubleshooting](#11-troubleshooting)
12. [Safe-use checklist](#12-safe-use-checklist)

---

## 1. Connect to MySQL

### Windows PowerShell — normal connection

Run this in PowerShell or Command Prompt. Enter the password when prompted.

```powershell
mysql -h 127.0.0.1 -P 3306 -u root -p
```

If Windows says `mysql` is not recognized, call the executable using its full path. Adjust the version/path if MySQL is installed elsewhere:

```powershell
& "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" -h 127.0.0.1 -P 3306 -u root -p
```

### Linux terminal

```bash
mysql -h 127.0.0.1 -P 3306 -u root -p
```

If the client is missing, install it using the package manager for your distribution:

```bash
# Ubuntu / Debian
sudo apt update
sudo apt install mysql-client
```

### Docker Compose

If this project uses Docker Compose to run MySQL, start its services from the directory containing `compose.yaml` or `docker-compose.yml`:

```bash
docker compose up -d
docker compose ps
```

> Do not run Docker commands unless this project actually uses Docker Compose.

### Password handling

Use `-p` and enter the password at the prompt. Avoid placing a password directly in the command, because it can be saved in shell history or exposed to other local processes. The password shared in the original notes is intentionally **not repeated in this file**. If that was a real password and this file or chat may be shared, change it.

---

## 2. Select the database and list tables

Run these commands **inside the MySQL prompt** (the prompt usually looks like `mysql>`):

```sql
USE room_allocation_db;
SHOW TABLES;
SELECT DATABASE();
```

**What each command does**

- `USE room_allocation_db;` selects the database for subsequent queries.
- `SHOW TABLES;` lists the tables in the selected database.
- `SELECT DATABASE();` confirms which database is currently selected.

To list tables without first entering the interactive MySQL prompt:

```bash
mysql -h 127.0.0.1 -P 3306 -u root -p -D room_allocation_db -e "SHOW TABLES;"
```

### Tables visible in the supplied screenshot

| Table | Likely purpose (confirm using its structure) |
|---|---|
| `student` | Student records |
| `room` | Exam rooms and capacities |
| `seat` | Physical seats or seat definitions |
| `seat_allocations` | Student-to-seat allocation records |
| `exam` | Examination records |
| `exam_rooms` | Rooms associated with exams |
| `exam_eligibility` | Eligibility rules or student/exam eligibility |
| `exam_semesters` | Semester associations for exams |
| `subjects` | Subject/course records |
| `departments` | Department records |
| `users` | Application user accounts |
| `notifications` | Notification records |
| `notification_templates` | Reusable notification templates |
| `notification_campaigns` | Notification campaigns |
| `campaign_deliveries` | Campaign delivery records |
| `push_subscriptions` | Browser/device push subscriptions |

These purposes are inferred from table names, not verified schema definitions. Use `DESCRIBE table_name;` before relying on columns or relationships.

---

## 3. Inspect table structures

### View columns and data types

```sql
DESCRIBE student;
DESCRIBE room;
DESCRIBE seat;
DESCRIBE seat_allocations;
DESCRIBE exam;
DESCRIBE exam_rooms;
DESCRIBE exam_eligibility;
DESCRIBE exam_semesters;
DESCRIBE subjects;
DESCRIBE departments;
DESCRIBE users;
```

**What it shows:** column names, data types, whether `NULL` is allowed, key information, default values, and extra attributes.

### Inspect the exact table definition

```sql
SHOW CREATE TABLE student;
SHOW CREATE TABLE room;
SHOW CREATE TABLE seat;
SHOW CREATE TABLE seat_allocations;
SHOW CREATE TABLE exam;
```

This shows the table's DDL, including columns, indexes, constraints, and foreign keys where defined.

### List all tables and their columns

```sql
SELECT
    TABLE_NAME,
    COLUMN_NAME,
    COLUMN_TYPE,
    IS_NULLABLE,
    COLUMN_KEY,
    COLUMN_DEFAULT,
    EXTRA
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'room_allocation_db'
ORDER BY TABLE_NAME, ORDINAL_POSITION;
```

### Inspect indexes

```sql
SHOW INDEX FROM student;
SHOW INDEX FROM room;
SHOW INDEX FROM seat_allocations;
```

### Check table sizes (approximate row counts for some storage engines)

```sql
SELECT
    TABLE_NAME,
    TABLE_ROWS,
    DATA_LENGTH,
    INDEX_LENGTH
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = 'room_allocation_db'
ORDER BY TABLE_NAME;
```

`TABLE_ROWS` can be an estimate for InnoDB tables; use `SELECT COUNT(*)` when an exact count is required.

---

## 4. Display data

### Display every row and column

```sql
SELECT * FROM student;
SELECT * FROM room;
SELECT * FROM seat;
SELECT * FROM seat_allocations;
SELECT * FROM exam;
SELECT * FROM exam_rooms;
SELECT * FROM exam_eligibility;
SELECT * FROM exam_semesters;
SELECT * FROM subjects;
SELECT * FROM departments;
SELECT * FROM users;
```

Use `SELECT *` mainly for inspection and development. Avoid displaying sensitive account or subscription data in screenshots or shared logs.

### Limit the number of rows

```sql
SELECT * FROM student LIMIT 20;
SELECT * FROM exam LIMIT 20;
SELECT * FROM seat_allocations LIMIT 20;
```

### Select specific columns

First check the table with `DESCRIBE`. Then select only columns that actually exist. For example, **if** `student` has `id`, `roll_no`, `name`, `branch`, and `batch`:

```sql
SELECT id, roll_no, name, branch, batch
FROM student
LIMIT 50;
```

The screenshot confirms the table names, but does not reveal their columns. Do not assume example columns exist until `DESCRIBE` confirms them.

---

## 5. Filter, sort, and search

Replace example values with values that exist in your database. Confirm column names with `DESCRIBE` first.

### Filter rows

```sql
SELECT *
FROM student
WHERE branch = 'CSE';

SELECT *
FROM exam
WHERE id = 1;
```

### Combine conditions

```sql
SELECT *
FROM student
WHERE branch = 'CSE'
  AND batch = 'A';
```

### Search by partial text

```sql
SELECT *
FROM student
WHERE name LIKE 'A%';
```

`A%` means values beginning with A. `%A%` means values containing A anywhere.

### Sort results

```sql
SELECT *
FROM exam
ORDER BY exam_date ASC;
```

If the table has an `exam_time` column too:

```sql
SELECT *
FROM exam
ORDER BY exam_date ASC, exam_time ASC;
```

### Find missing values

```sql
SELECT *
FROM student
WHERE name IS NULL;
```

Use `IS NULL`, not `= NULL`, to test for SQL `NULL` values.

---

## 6. Count and summarize records

### Count rows in a table

```sql
SELECT COUNT(*) AS total_students FROM student;
SELECT COUNT(*) AS total_rooms FROM room;
SELECT COUNT(*) AS total_seats FROM seat;
SELECT COUNT(*) AS total_exams FROM exam;
SELECT COUNT(*) AS total_allocations FROM seat_allocations;
```

### Count rows for every table

```sql
SELECT 'student' AS table_name, COUNT(*) AS row_count FROM student
UNION ALL
SELECT 'room', COUNT(*) FROM room
UNION ALL
SELECT 'seat', COUNT(*) FROM seat
UNION ALL
SELECT 'seat_allocations', COUNT(*) FROM seat_allocations
UNION ALL
SELECT 'exam', COUNT(*) FROM exam
UNION ALL
SELECT 'exam_rooms', COUNT(*) FROM exam_rooms
UNION ALL
SELECT 'exam_eligibility', COUNT(*) FROM exam_eligibility
UNION ALL
SELECT 'exam_semesters', COUNT(*) FROM exam_semesters
UNION ALL
SELECT 'subjects', COUNT(*) FROM subjects
UNION ALL
SELECT 'departments', COUNT(*) FROM departments
UNION ALL
SELECT 'users', COUNT(*) FROM users;
```

Add the other table names from `SHOW TABLES` if you want their counts as well.

### Group records by a category

If `student.branch` exists:

```sql
SELECT branch, COUNT(*) AS student_count
FROM student
GROUP BY branch
ORDER BY student_count DESC;
```

If `room` has a `capacity` column:

```sql
SELECT SUM(capacity) AS total_room_capacity
FROM room;
```

These are examples only; confirm the relevant columns first.

---

## 7. Join related tables

A `JOIN` combines rows from related tables. Use `DESCRIBE` and `SHOW CREATE TABLE` to verify the actual foreign-key columns before running a join.

### Inspect declared foreign keys

```sql
SELECT
    TABLE_NAME,
    COLUMN_NAME,
    CONSTRAINT_NAME,
    REFERENCED_TABLE_NAME,
    REFERENCED_COLUMN_NAME
FROM information_schema.KEY_COLUMN_USAGE
WHERE TABLE_SCHEMA = 'room_allocation_db'
  AND REFERENCED_TABLE_NAME IS NOT NULL
ORDER BY TABLE_NAME, COLUMN_NAME;
```

### Example join pattern

The following is a **template**. Replace the column names with those confirmed in your schema:

```sql
SELECT
    a.*,
    s.*,
    e.*
FROM seat_allocations AS a
JOIN student AS s ON s.id = a.student_id
JOIN exam AS e ON e.id = a.exam_id
LIMIT 100;
```

Run this only if `seat_allocations.student_id`, `seat_allocations.exam_id`, `student.id`, and `exam.id` exist and represent those relationships. If not, use the foreign-key inspection query above to determine the correct join.

### See which rooms are associated with exams

```sql
SELECT *
FROM exam_rooms
LIMIT 100;
```

Inspect `DESCRIBE exam_rooms;` first, then join it to `exam` and `room` using the actual key columns.

---

## 8. Inspect one record and relationships

### Find a record by its ID

```sql
SELECT * FROM student WHERE id = 1;
SELECT * FROM room WHERE id = 1;
SELECT * FROM exam WHERE id = 1;
SELECT * FROM seat_allocations WHERE id = 1;
```

These assume the tables have an `id` column. If they do not, use the primary-key column shown by `DESCRIBE`.

### Find related rows

After confirming the foreign-key column names, use a query like:

```sql
SELECT *
FROM seat_allocations
WHERE exam_id = 1;
```

This example requires an `exam_id` column in `seat_allocations`.

---

## 9. Export results and back up the database

Run export commands in **PowerShell, Command Prompt, or a Linux terminal**, not inside the `mysql>` prompt.

### Export selected data to a tab-separated file

```bash
mysql -h 127.0.0.1 -P 3306 -u root -p -D room_allocation_db \
  --batch --raw \
  -e "SELECT * FROM student" > students.tsv
```

This creates `students.tsv` in the current terminal directory. Despite a common `.csv` extension, MySQL batch output is tab-separated by default. For a true CSV, use an export tool or a query/client workflow that correctly quotes commas, quotes, and newlines.

### Show a readable table in the terminal

```bash
mysql -h 127.0.0.1 -P 3306 -u root -p -D room_allocation_db \
  --table -e "SELECT * FROM exam LIMIT 20"
```

### Back up the schema and data

```bash
mysqldump -h 127.0.0.1 -P 3306 -u root -p room_allocation_db > room_allocation_db.sql
```

If Windows cannot find `mysqldump`, use its full path, for example:

```powershell
& "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqldump.exe" -h 127.0.0.1 -P 3306 -u root -p room_allocation_db > room_allocation_db.sql
```

### Restore a backup — destructive if pointed at the wrong database

Only restore after confirming the target database and backup file. A restore can overwrite or alter existing objects/data.

```bash
mysql -h 127.0.0.1 -P 3306 -u root -p room_allocation_db < room_allocation_db.sql
```

Keep backups in a safe location and test restoration before relying on them.

---

## 10. MySQL Workbench tips

The screenshot shows a MySQL Workbench-style SQL editor and result grid.

1. Open the local MySQL connection.
2. Select `room_allocation_db` in the schema panel, or run `USE room_allocation_db;`.
3. Enter a query in the SQL editor, such as:

   ```sql
   SHOW TABLES;
   ```

4. Click the lightning-bolt **Execute** button, or use the Workbench execution shortcut shown in its menus.
5. Read the results in **Result Grid**.
6. To inspect a table's columns, run:

   ```sql
   DESCRIBE student;
   ```

7. To display table data, run:

   ```sql
   SELECT * FROM student LIMIT 50;
   ```

Use `LIMIT` when exploring large tables so the result grid remains manageable.

---

## 11. Troubleshooting

### `Unknown database 'room_allocation_db'`

Check the available databases:

```sql
SHOW DATABASES;
```

Confirm the database name and whether the MySQL server you connected to is the correct local instance.

### `Table doesn't exist`

```sql
USE room_allocation_db;
SHOW TABLES;
```

MySQL table-name case sensitivity can differ by operating system and server configuration. Use the exact table name returned by `SHOW TABLES`.

### `Unknown column`

Inspect the actual structure:

```sql
DESCRIBE student;
SHOW CREATE TABLE student;
```

Change the query to use the column names shown by MySQL. Do not rely on a schema example from another version of the application.

### `Access denied for user`

Verify the username, password, host, and privileges. Do not delete a Docker volume just to fix a login problem: `docker compose down -v` can delete the database's persistent data. Back up first and confirm the cause.

### `Can't connect to MySQL server` / connection refused

Check whether the server is running and listening on port 3306.

Windows PowerShell:

```powershell
Test-NetConnection 127.0.0.1 -Port 3306
```

Linux:

```bash
ss -ltn | grep 3306
```

If using Docker Compose:

```bash
docker compose ps
docker compose logs --tail=100
```

### Check the current connection

```sql
SELECT CURRENT_USER(), DATABASE(), VERSION(), NOW();
SHOW PROCESSLIST;
```

`SHOW PROCESSLIST` may reveal connection details, so do not publish its output without reviewing it.

---

## 12. Safe-use checklist

- Prefer `SELECT`, `SHOW`, and `DESCRIBE` while learning the schema.
- Confirm columns and foreign keys before writing joins.
- Avoid `DELETE`, `UPDATE`, `DROP`, and `TRUNCATE` unless you know the effect and have a backup.
- Never run `docker compose down -v` casually; it can remove persistent database volumes.
- Do not save database passwords in this guide, source control, screenshots, or shell history.
- Before sharing output, check for student personal information, password hashes, tokens, notification details, and push-subscription data.
- Back up important data before schema changes or imports.

## Quick reference

| Goal | Command |
|---|---|
| Select database | `USE room_allocation_db;` |
| List tables | `SHOW TABLES;` |
| Inspect columns | `DESCRIBE student;` |
| Inspect table DDL | `SHOW CREATE TABLE student;` |
| Show a sample | `SELECT * FROM student LIMIT 20;` |
| Count records | `SELECT COUNT(*) FROM student;` |
| Current database | `SELECT DATABASE();` |
| Connection details | `SELECT CURRENT_USER(), VERSION(), NOW();` |
| Exit MySQL client | `EXIT;` |

---

**Schema note:** The supplied screenshot verifies the table names listed near the top. It does not establish every table's column names or foreign-key relationships. This guide labels those queries as examples/templates where the schema must be checked first.
