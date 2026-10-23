package com.exam.allocation.service;

import com.exam.allocation.model.Exam;
import com.exam.allocation.repository.ExamRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Imports exam data uploaded as PDF, Excel (.xls/.xlsx) or CSV/TXT files.
 *
 * <p>Extracted per row: <b>subject, branch, batch/course, date, time and
 * application ID</b> (whichever the file provides). The date and time cells are
 * located by content, so shifted PDF tables still parse. Rows are validated:
 * a row without a subject or without a parseable date is reported as invalid
 * and not stored. Valid rows are saved as {@link Exam} entities, where they
 * immediately become available to the timetable/seating allocation. Clashes
 * created by the import (same date + time + audience) are reported back.
 */
@Service
public class ExamImportService {

    @Autowired
    private ExamRepository examRepository;

    @Autowired
    private FileTableReader fileTableReader;

    @Autowired
    private ConflictService conflictService;

    @Autowired
    private BatchService batchService;

    public static class ExamImportResult {
        private String fileName;
        private int imported;   // new exams created
        private int updated;    // existing exams (same subject + date) updated
        private int invalid;    // rows rejected during validation
        private int skipped;    // blank rows
        private List<Map<String, String>> preview = new ArrayList<>();
        private List<String> notes = new ArrayList<>();
        private List<String> conflicts = new ArrayList<>();

        public String getFileName() { return fileName; }
        public void setFileName(String fileName) { this.fileName = fileName; }
        public int getImported() { return imported; }
        public void setImported(int imported) { this.imported = imported; }
        public int getUpdated() { return updated; }
        public void setUpdated(int updated) { this.updated = updated; }
        public int getInvalid() { return invalid; }
        public void setInvalid(int invalid) { this.invalid = invalid; }
        public int getSkipped() { return skipped; }
        public void setSkipped(int skipped) { this.skipped = skipped; }
        public List<Map<String, String>> getPreview() { return preview; }
        public void setPreview(List<Map<String, String>> preview) { this.preview = preview; }
        public List<String> getNotes() { return notes; }
        public void setNotes(List<String> notes) { this.notes = notes; }
        public List<String> getConflicts() { return conflicts; }
        public void setConflicts(List<String> conflicts) { this.conflicts = conflicts; }
    }

    private static final String F_SUBJECT = "subjectName";
    private static final String F_BRANCH = "branch";
    private static final String F_COURSE = "course";
    private static final String F_DATE = "examDate";
    private static final String F_TIME = "examTime";
    private static final String F_APP = "applicationId";

    // ------------------------------------------------------------------
    // Public entry point
    // ------------------------------------------------------------------

    public ExamImportResult importExams(MultipartFile file) throws IOException {
        List<String[]> rows = fileTableReader.read(file);
        String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();

        ExamImportResult result = new ExamImportResult();
        result.setFileName(original);

        // ---- Header detection -------------------------------------------------
        int headerRow = -1;
        Map<String, Integer> mapping = new HashMap<>();
        for (int i = 0; i < Math.min(rows.size(), 5); i++) {
            Map<String, Integer> candidate = mapHeader(rows.get(i));
            if (candidate.size() < 2) {
                // PDF rows may be a single blob of single-space separated columns.
                String[] atoms = toAtoms(rows.get(i));
                if (atoms.length != rows.get(i).length) candidate = mapHeader(atoms);
            }
            if (candidate.size() >= 2) {
                headerRow = i;
                mapping = candidate;
                break;
            }
        }
        boolean ambiguousCourseHeader = false;
        if (headerRow < 0) {
            mapping.put(F_SUBJECT, 0);
            mapping.put(F_BRANCH, 1);
            mapping.put(F_COURSE, 2);
            mapping.put(F_DATE, 3);
            mapping.put(F_TIME, 4);
            result.getNotes().add("No header row recognised - assumed column order: "
                    + "Subject, Branch, Batch, Date, Time.");
        } else {
            ambiguousCourseHeader = headerCellIs(toAtoms(rows.get(headerRow)), mapping.get(F_COURSE), "course");
            result.getNotes().add("Header found in row " + (headerRow + 1) + " with "
                    + mapping.size() + " recognised column(s).");
        }

        // ---- Row loop ----------------------------------------------------------
        Set<String> seenKeys = new HashSet<>();
        Set<Long> touchedIds = new HashSet<>();
        Set<String> generatedAppIds = new HashSet<>();
        int startRow = headerRow >= 0 ? headerRow + 1 : 0;
        int processed = 0;

        for (int i = startRow; i < rows.size(); i++) {
            String[] cells = rows.get(i);
            if (isBlankRow(cells)) { result.setSkipped(result.getSkipped() + 1); continue; }
            if (headerRow >= 0 && looksLikeHeader(cells)) { result.setSkipped(result.getSkipped() + 1); continue; }

            int rowNo = i + 1;
            Map<String, String> fields = extract(cells, mapping);

            String subject = blankToNull(fields.get(F_SUBJECT));
            String branch = blankToNull(fields.get(F_BRANCH));
            String course = blankToNull(fields.get(F_COURSE));
            if (ambiguousCourseHeader && course != null) {
                if (!looksLikeBatch(course)) {
                    if (branch == null) branch = course;
                    course = null;
                }
            }
            branch = wildcardToNull(branch);
            course = wildcardToNull(course);
            if (course != null && (batchService.isKnown(course)
                    || (course.length() == 1 && Character.isLetter(course.charAt(0))))) {
                batchService.ensureExists(course);   // batch found in the file -> usable by scheduling
            }

            LocalDate date = parseDateValue(fields.get(F_DATE));
            LocalTime time = parseTimeValue(fields.get(F_TIME));
            String rawDate = blankToNull(fields.get(F_DATE));
            String rawTime = blankToNull(fields.get(F_TIME));

            // ---- validation ----
            if (subject == null) {
                invalid(result, "Row " + rowNo + ": no subject/exam name found - row skipped.");
                continue;
            }
            if (date == null) {
                invalid(result, "Row " + rowNo + ": could not parse date"
                        + (rawDate == null ? " (date cell empty)" : " \"" + rawDate + "\"")
                        + " for \"" + subject + "\" - row skipped.");
                continue;
            }
            if (rawTime != null && time == null && result.getNotes().size() < 20) {
                result.getNotes().add("Row " + rowNo + ": time \"" + rawTime
                        + "\" could not be parsed for \"" + subject + "\" - stored without a time.");
            }

            String key = subject.toLowerCase(Locale.ROOT) + "|" + date;
            boolean duplicate = !seenKeys.add(key);
            Exam exam = findExisting(subject, date);
            boolean isNew = exam == null;
            if (isNew) exam = new Exam();

            exam.setSubjectName(subject);
            exam.setBranch(branch);
            exam.setCourse(course);
            exam.setExamDate(date);
            exam.setExamTime(time);
            if (blankToNull(fields.get(F_APP)) != null) {
                exam.setApplicationId(blankToNull(fields.get(F_APP)));
            } else if (exam.getApplicationId() == null || exam.getApplicationId().isBlank()) {
                exam.setApplicationId(generateApplicationId(course, branch, date, generatedAppIds));
            }
            exam = examRepository.save(exam);
            touchedIds.add(exam.getId());
            processed++;

            if (isNew) result.setImported(result.getImported() + 1);
            else result.setUpdated(result.getUpdated() + 1);
            if (duplicate && result.getNotes().size() + 1 <= 20) {
                result.getNotes().add("Row " + rowNo + ": duplicate of an earlier row (\"" + subject
                        + "\" on " + date + ") - updated the existing exam.");
            }

            if (result.getPreview().size() < 10) {
                Map<String, String> line = new LinkedHashMap<>();
                line.put("subjectName", exam.getSubjectName());
                line.put("branch", exam.getBranch());
                line.put("course", exam.getCourse());
                line.put("examDate", exam.getExamDate().toString());
                line.put("examTime", exam.getExamTime() == null ? null : exam.getExamTime().toString());
                line.put("applicationId", exam.getApplicationId());
                line.put("status", isNew ? "NEW" : "UPDATED");
                result.getPreview().add(line);
            }
        }

        // ---- conflicts caused by the imported exams ----------------------------
        for (ConflictService.Conflict conflict : conflictService.findConflictsFor(touchedIds)) {
            result.getConflicts().add(conflict.getMessage());
        }

        if (processed == 0) {
            result.getNotes().add("No valid exam rows found in the file.");
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Row extraction
    // ------------------------------------------------------------------

    /**
     * Extracts the mapped fields of one row. Date and time cells are located by
     * content first (they are never stolen by another column), which makes the
     * parser robust against shifted/irregular PDF tables.
     */
    private Map<String, String> extract(String[] cells, Map<String, Integer> mapping) {
        cells = toAtoms(cells);

        int dateIdx = -1;
        Integer mappedDate = mapping.get(F_DATE);
        if (mappedDate != null && mappedDate < cells.length && parseDateValue(cells[mappedDate]) != null) {
            dateIdx = mappedDate;
        }
        if (dateIdx < 0) {
            for (int i = 0; i < cells.length; i++) {
                if (looksLikeDateText(cells[i]) && parseDateValue(cells[i]) != null) { dateIdx = i; break; }
            }
        }

        int timeIdx = -1;
        Integer mappedTime = mapping.get(F_TIME);
        if (mappedTime != null && mappedTime < cells.length && parseTimeValue(cells[mappedTime]) != null) {
            timeIdx = mappedTime;
        }
        if (timeIdx < 0) {
            for (int i = 0; i < cells.length; i++) {
                if (i != dateIdx && looksLikeTimeText(cells[i]) && parseTimeValue(cells[i]) != null) {
                    timeIdx = i;
                    break;
                }
            }
        }

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(F_DATE, dateIdx >= 0 ? cells[dateIdx] : valueAt(cells, mapping.get(F_DATE)));
        fields.put(F_TIME, timeIdx >= 0 ? cells[timeIdx] : valueAt(cells, mapping.get(F_TIME)));

        Set<Integer> consumed = new HashSet<>();
        if (dateIdx >= 0) consumed.add(dateIdx);
        if (timeIdx >= 0) consumed.add(timeIdx);

        for (String field : new String[]{F_SUBJECT, F_BRANCH, F_COURSE, F_APP}) {
            Integer idx = mapping.get(field);
            String value = null;
            if (idx != null && idx < cells.length && !consumed.contains(idx)) {
                value = cells[idx];
                if (field.equals(F_SUBJECT) && blankToNull(value) != null) consumed.add(idx);
                if (!field.equals(F_SUBJECT) && blankToNull(value) != null) consumed.add(idx);
            }
            fields.put(field, value);
        }

        // Subject fallback: when the mapped subject cell held the date/time (or was
        // empty), take the first remaining text cell that is not used by another column.
        if (blankToNull(fields.get(F_SUBJECT)) == null) {
            Integer subjIdx = mapping.get(F_SUBJECT);
            Integer branchIdx = mapping.get(F_BRANCH);
            Integer courseIdx = mapping.get(F_COURSE);
            Integer appIdx = mapping.get(F_APP);
            for (int i = 0; i < cells.length; i++) {
                if (consumed.contains(i)) continue;
                if ((subjIdx != null && subjIdx == i) || (branchIdx != null && branchIdx == i)
                        || (courseIdx != null && courseIdx == i) || (appIdx != null && appIdx == i)) continue;
                String value = blankToNull(cells[i]);
                if (value != null && value.matches(".*[A-Za-z].*")) {
                    fields.put(F_SUBJECT, value);
                    break;
                }
            }
        }
        return fields;
    }

    private String valueAt(String[] cells, Integer idx) {
        return (idx == null || idx >= cells.length) ? null : cells[idx];
    }

    // ------------------------------------------------------------------
    // Header mapping
    // ------------------------------------------------------------------

    private Map<String, Integer> mapHeader(String[] cells) {
        Map<String, Integer> mapping = new HashMap<>();
        for (int i = 0; i < cells.length && i < 40; i++) {
            String field = aliasFor(cells[i]);
            if (field != null && !mapping.containsKey(field)) {
                mapping.put(field, i);
            }
        }
        return mapping;
    }

    private boolean looksLikeHeader(String[] cells) {
        if (cells == null) return false;
        if (mapHeader(cells).size() >= 2) return true;
        String[] atoms = toAtoms(cells);
        return atoms.length != cells.length && mapHeader(atoms).size() >= 2;
    }

    /**
     * A PDF row may arrive as one blob of single-space separated columns
     * (PDFBox collapses the wide table gaps). Split such a row into atoms so
     * the column mapping and content scanning work on individual values.
     */
    private String[] toAtoms(String[] cells) {
        if (cells == null) return new String[0];
        if (cells.length == 1 && cells[0] != null && cells[0].trim().contains(" ")) {
            String[] tokens = cells[0].trim().split("\\s+");
            if (tokens.length > 1) return tokens;
        }
        return cells;
    }

    private boolean headerCellIs(String[] headerCells, Integer index, String expected) {
        if (headerCells == null || index == null || index >= headerCells.length) return false;
        String raw = headerCells[index];
        if (raw == null) return false;
        return raw.trim().toLowerCase().replaceAll("[.:]", "").replaceAll("\\s+", " ").equals(expected);
    }

    private String aliasFor(String raw) {
        if (raw == null) return null;
        String header = raw.trim().toLowerCase().replaceAll("[.:]", "").replaceAll("\\s+", " ");
        switch (header) {
            case "subject": case "subject name": case "subjectname": case "paper": case "paper name":
            case "exam": case "exam name": case "examname": case "exam subject": case "title":
            case "paper title":
                return F_SUBJECT;
            case "branch": case "dept": case "department": case "stream":
                return F_BRANCH;
            case "course": case "batch": case "batch/course": case "batch / course": case "class":
            case "section": case "group": case "program": case "programme":
                return F_COURSE;
            case "date": case "exam date": case "examdate": case "exam day": case "day":
                return F_DATE;
            case "time": case "exam time": case "examtime": case "timing": case "session":
            case "start time":
                return F_TIME;
            case "application id": case "applicationid": case "app id": case "appid":
            case "reference no": case "ref no": case "exam id":
                return F_APP;
            default:
                return null;
        }
    }

    // ------------------------------------------------------------------
    // Date / time parsing
    // ------------------------------------------------------------------

    private static final Pattern NUMERIC_DMY = Pattern.compile("^(\\d{1,2})[-/.](\\d{1,2})[-/.](\\d{2,4})$");
    private static final Pattern NUMERIC_YMD = Pattern.compile("^(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})$");
    private static final Pattern DMY_NAME = Pattern.compile(
            "^(\\d{1,2})[-\\s]([A-Za-z]{3,9})[-\\s](\\d{4})$");
    private static final Pattern NAME_DMY = Pattern.compile(
            "^([A-Za-z]{3,9})[-\\s]+(\\d{1,2})[-\\s,]+(\\d{4})$");
    private static final Pattern DATE_LIKE = Pattern.compile(
            "^\\d{1,4}[-/.]\\d{1,2}[-/.]\\d{1,4}$|^\\d{1,2}\\s+[A-Za-z]{3,}\\s+\\d{4}$"
                    + "|^[A-Za-z]{3,}\\s+\\d{1,2},?\\s+\\d{4}$");
    private static final Pattern TIME_LIKE = Pattern.compile(
            "^\\d{1,2}:\\d{2}(?:\\s*[AaPp][Mm])?$|^\\d{1,2}\\s*[AaPp][Mm]$");

    /**
     * Parses the many date formats found in exam schedules:
     * yyyy-MM-dd, dd/MM/yyyy, dd-MM-yyyy, d.M.yyyy, dd MMM yyyy, MMM d yyyy,
     * yyyyMMdd, and Excel serial dates (5-digit numbers).
     * @return the parsed date, or null when the value is missing/unparseable
     */
    public static LocalDate parseDateValue(String raw) {
        if (raw == null) return null;
        String v = raw.trim();
        if (v.isEmpty() || v.length() > 20) return null;

        try {
            Matcher ymd = NUMERIC_YMD.matcher(v);
            if (ymd.matches()) {
                return LocalDate.of(Integer.parseInt(ymd.group(1)),
                        Integer.parseInt(ymd.group(2)), Integer.parseInt(ymd.group(3)));
            }

            Matcher dmy = NUMERIC_DMY.matcher(v);
            if (dmy.matches()) {
                int first = Integer.parseInt(dmy.group(1));
                int second = Integer.parseInt(dmy.group(2));
                int day, month;
                if (second > 12 && first <= 12) { month = first; day = second; }      // M/d/yyyy
                else { day = first; month = second; }                                 // d/M/yyyy (default)
                int year = Integer.parseInt(dmy.group(3));
                if (dmy.group(3).length() == 2) year += 2000;
                return LocalDate.of(year, month, day);
            }

            if (v.matches("^\\d{8}$")) {
                int year = Integer.parseInt(v.substring(0, 4));
                if (year >= 1900 && year <= 2200) {
                    return LocalDate.of(Integer.parseInt(v.substring(0, 4)),
                            Integer.parseInt(v.substring(4, 6)), Integer.parseInt(v.substring(6, 8)));
                }
                return LocalDate.of(Integer.parseInt(v.substring(4, 8)),
                        Integer.parseInt(v.substring(2, 4)), Integer.parseInt(v.substring(0, 2)));
            }

            // Excel serial date (epoch1899-12-30), only in a plausible range
            if (v.matches("^\\d{5}$")) {
                int serial = Integer.parseInt(v);
                if (serial >= 20000 && serial <= 60000) {
                    return LocalDate.of(1899, 12, 30).plusDays(serial);
                }
                return null;
            }

            Matcher named1 = DMY_NAME.matcher(v);
            if (named1.matches()) {
                return LocalDate.of(Integer.parseInt(named1.group(3)),
                        monthFromName(named1.group(2)), Integer.parseInt(named1.group(1)));
            }
            Matcher named2 = NAME_DMY.matcher(v);
            if (named2.matches()) {
                return LocalDate.of(Integer.parseInt(named2.group(3)),
                        monthFromName(named2.group(1)), Integer.parseInt(named2.group(2)));
            }
        } catch (RuntimeException ex) {
            return null;   // out-of-range day/month etc.
        }
        return null;
    }

    /** True when a cell at least looks like a date (used when scanning a row). */
    public static boolean looksLikeDateText(String raw) {
        return raw != null && DATE_LIKE.matcher(raw.trim()).matches();
    }

    public static boolean looksLikeTimeText(String raw) {
        return raw != null && TIME_LIKE.matcher(raw.trim()).matches();
    }

    private static int monthFromName(String name) {
        String[] months = {"january", "february", "march", "april", "may", "june", "july",
                "august", "september", "october", "november", "december"};
        String lower = name.toLowerCase(Locale.ROOT);
        for (int i = 0; i < months.length; i++) {
            if (months[i].equals(lower) || months[i].substring(0, 3).equals(lower)) {
                return i + 1;
            }
        }
        throw new IllegalArgumentException("Unknown month: " + name);
    }

    /**
     * Parses "10:00", "10:00 AM", "09.30", "0930", "9 pm", ...
     * @return the parsed time, or null when missing/unparseable
     */
    public static LocalTime parseTimeValue(String raw) {
        if (raw == null) return null;
        String v = raw.trim().toUpperCase(Locale.ROOT).replace('.', ':');
        if (v.isEmpty() || v.length() > 12) return null;
        try {
            Matcher ampm = Pattern.compile("^(\\d{1,2}):(\\d{2})\\s*([AP]M)$").matcher(v);
            if (ampm.matches()) {
                int hour = Integer.parseInt(ampm.group(1));
                int min = Integer.parseInt(ampm.group(2));
                if (hour < 1 || hour > 12 || min > 59) return null;
                if (ampm.group(3).equals("PM") && hour != 12) hour += 12;
                if (ampm.group(3).equals("AM") && hour == 12) hour = 0;
                return LocalTime.of(hour, min);
            }
            Matcher hm = Pattern.compile("^(\\d{1,2}):(\\d{1,2})$").matcher(v);
            if (hm.matches()) {
                int hour = Integer.parseInt(hm.group(1));
                int min = Integer.parseInt(hm.group(2));
                if (hour > 23 || min > 59) return null;
                return LocalTime.of(hour, min);
            }
            Matcher bareAmpm = Pattern.compile("^(\\d{1,2})\\s*([AP]M)$").matcher(v);
            if (bareAmpm.matches()) {
                int hour = Integer.parseInt(bareAmpm.group(1));
                if (hour < 1 || hour > 12) return null;
                if (bareAmpm.group(2).equals("PM") && hour != 12) hour += 12;
                if (bareAmpm.group(2).equals("AM") && hour == 12) hour = 0;
                return LocalTime.of(hour, 0);
            }
            if (v.matches("^\\d{3,4}$")) {          // "0930" / "930"
                String padded = v.length() == 3 ? "0" + v : v;
                int hour = Integer.parseInt(padded.substring(0, 2));
                int min = Integer.parseInt(padded.substring(2));
                if (hour > 23 || min > 59) return null;
                return LocalTime.of(hour, min);
            }
        } catch (RuntimeException ex) {
            return null;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void invalid(ExamImportResult result, String reason) {
        result.setInvalid(result.getInvalid() + 1);
        if (result.getNotes().size() < 20) result.getNotes().add(reason);
    }

    private Exam findExisting(String subject, LocalDate date) {
        for (Exam exam : examRepository.findAll()) {
            if (exam.getSubjectName() != null
                    && exam.getSubjectName().trim().equalsIgnoreCase(subject)
                    && date.equals(exam.getExamDate())) {
                return exam;
            }
        }
        return null;
    }

    private String generateApplicationId(String course, String branch, LocalDate date,
                                         Set<String> alreadyGenerated) {
        String tag = course != null ? course : (branch != null ? branch : "EX");
        tag = tag.trim().replaceAll("[^A-Za-z0-9]+", "").toUpperCase(Locale.ROOT);
        if (tag.isEmpty()) tag = "EX";
        String base = "APP-" + tag + "-" + date.toString().replace("-", "");
        int n = 1;
        String candidate = base + "-001";
        while (alreadyGenerated.contains(candidate) || existsApplicationId(candidate)) {
            n++;
            candidate = base + "-" + String.format("%03d", n);
        }
        alreadyGenerated.add(candidate);
        return candidate;
    }

    private boolean existsApplicationId(String applicationId) {
        for (Exam exam : examRepository.findAll()) {
            if (applicationId.equals(exam.getApplicationId())) return true;
        }
        return false;
    }

    private boolean looksLikeBatch(String value) {
        if (value == null) return true;
        String trimmed = value.trim();
        if (batchService.isKnown(trimmed)) return true;
        return trimmed.length() == 1 && Character.isLetter(trimmed.charAt(0));
    }

    private String wildcardToNull(String value) {
        if (value == null) return null;
        String lower = value.trim().toLowerCase(Locale.ROOT);
        if (lower.isEmpty() || lower.equals("all") || lower.equals("*") || lower.equals("any")
                || lower.equals("-") || lower.equals("--")) {
            return null;
        }
        return value.trim();
    }

    private boolean isBlankRow(String[] cells) {
        if (cells == null || cells.length == 0) return true;
        for (String cell : cells) {
            if (cell != null && !cell.trim().isEmpty()) return false;
        }
        return true;
    }

    private String blankToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
