package com.exam.allocation.service;

import com.exam.allocation.model.Student;
import com.exam.allocation.repository.StudentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Imports student lists uploaded as Excel (.xls/.xlsx), PDF or CSV/TXT files.
 *
 * <p>The stored student fields are exactly the four required by the
 * specification: <b>Roll Number, Name, Branch, Batch/Course</b> (no Division).
 *
 * <p>Header row is detected by known column names ("roll no", "name", "branch",
 * "batch", "course", ...); when it cannot be detected the default order
 * rollNo/name/branch/batch is used. A column headed <i>course</i> is treated as
 * ambiguous: single-letter or already known values (A..G, custom batches) go to
 * <i>batch</i>, everything else (CSE, CEC, ...) goes to <i>branch</i>. Batch
 * values found in the file are registered automatically so they become
 * available to the timetable system. Any cell that is missing in the file is
 * stored as null.
 */
@Service
public class ImportService {

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private FileTableReader fileTableReader;

    @Autowired
    private BatchService batchService;

    @Autowired
    private UserAccountService userAccountService;

    public static class ImportResult {
        private String fileName;
        private int imported;     // brand-new students
        private int updated;      // existing roll numbers updated
        private int skipped;      // empty / unrecognised rows
        private int missingCells; // cells stored as null
        private List<Map<String, String>> preview = new ArrayList<>();
        private List<String> notes = new ArrayList<>();

        public String getFileName() { return fileName; }
        public void setFileName(String fileName) { this.fileName = fileName; }
        public int getImported() { return imported; }
        public void setImported(int imported) { this.imported = imported; }
        public int getUpdated() { return updated; }
        public void setUpdated(int updated) { this.updated = updated; }
        public int getSkipped() { return skipped; }
        public void setSkipped(int skipped) { this.skipped = skipped; }
        public int getMissingCells() { return missingCells; }
        public void setMissingCells(int missingCells) { this.missingCells = missingCells; }
        public List<Map<String, String>> getPreview() { return preview; }
        public void setPreview(List<Map<String, String>> preview) { this.preview = preview; }
        public List<String> getNotes() { return notes; }
        public void setNotes(List<String> notes) { this.notes = notes; }
    }

    private static final String F_ROLL = "rollNo";
    private static final String F_NAME = "name";
    private static final String F_BRANCH = "branch";
    private static final String F_BATCH = "batch";

    public ImportResult importStudents(MultipartFile file) throws IOException {
        List<String[]> rows = fileTableReader.read(file);
        String originalName = file.getOriginalFilename();
        String original = originalName == null ? "" : originalName;

        ImportResult result = new ImportResult();
        result.setFileName(original);

        // ---- Locate header row and map columns -------------------------------
        int headerRow = -1;
        Map<String, Integer> mapping = new HashMap<>();
        int scanLimit = Math.min(rows.size(), 5);
        for (int i = 0; i < scanLimit; i++) {
            Map<String, Integer> candidate = mapHeader(rows.get(i));
            if (candidate.size() >= 2) {
                headerRow = i;
                mapping = candidate;
                break;
            }
            // PDF blob rows: only accept a tokenised header when its labels are
            // single-token per column ("Roll No" would shift every index), which
            // is the case for Excel/CSV anyway - PDFs fall back to the default order.
        }
        boolean ambiguousCourseHeader = false;
        if (headerRow < 0) {
            // No recognised header: assume the default column order.
            mapping.put(F_ROLL, 0);
            mapping.put(F_NAME, 1);
            mapping.put(F_BRANCH, 2);
            mapping.put(F_BATCH, 3);
            result.getNotes().add("No header row recognised - assumed column order: Roll No, Name, Branch, Batch.");
        } else {
            ambiguousCourseHeader = headerCellIs(toAtoms(rows.get(headerRow)), mapping.get(F_BATCH), "course");
            result.getNotes().add("Header found in row " + (headerRow + 1) + " with "
                    + mapping.size() + " recognised column(s).");
        }

        int expected = mapping.values().stream().mapToInt(v -> v).max().orElse(3) + 1;
        int nameIdx = mapping.getOrDefault(F_NAME, 1);

        // ---- Read each row into fields ---------------------------------------
        int startRow = headerRow >= 0 ? headerRow + 1 : 0;
        int processed = 0;
        int rerouted = 0;
        for (int i = startRow; i < rows.size(); i++) {
            String[] cells = rows.get(i);
            if (isBlankRow(cells)) { result.setSkipped(result.getSkipped() + 1); continue; }
            if (headerRow < 0 && looksLikeHeader(cells)) {
                // A header that was not recognised by the first-pass scan (e.g. on page 2).
                result.setSkipped(result.getSkipped() + 1);
                continue;
            }

            boolean positionalTokens = original.toLowerCase().endsWith(".pdf");
            Map<String, String> fields = positionalTokens
                    ? extractByTokens(cells, mapping, expected, nameIdx)
                    : extractPositional(cells, mapping);

            if (ambiguousCourseHeader) {
                if (routeCourseValue(fields)) rerouted++;
            }

            String roll = blankToNull(fields.get(F_ROLL));
            String name = blankToNull(fields.get(F_NAME));
            String branch = blankToNull(fields.get(F_BRANCH));
            String batch = normaliseBatch(fields.get(F_BATCH));

            // PDF tables drop empty cells, so values shift left: a row such as
            // "402 Asha A" (missing branch) puts the batch value into the branch
            // column - move it back when it is clearly batch-like.
            if (positionalTokens && batch == null && branch != null && looksLikeBatch(branch)) {
                batch = branch;
                branch = null;
                rerouted++;
            }

            if (roll == null && name == null && branch == null && batch == null) {
                result.setSkipped(result.getSkipped() + 1);
                continue;
            }

            int missing = countMissing(roll, name, branch, batch);
            result.setMissingCells(result.getMissingCells() + missing);

            // Make batches found in the file available to the timetable system.
            if (batch != null) batchService.ensureExists(batch);

            Student student;
            if (roll != null) {
                Student existing = studentRepository.findByRollNo(roll);
                if (existing != null) {
                    existing.setName(name);
                    existing.setBranch(branch);
                    existing.setBatch(batch);
                    student = studentRepository.save(existing);
                    result.setUpdated(result.getUpdated() + 1);
                } else {
                    student = studentRepository.save(newStudent(roll, name, branch, batch));
                    result.setImported(result.getImported() + 1);
                }
            } else {
                student = studentRepository.save(newStudent(null, name, branch, batch));
                result.setImported(result.getImported() + 1);
            }

            if (result.getPreview().size() < 10) {
                Map<String, String> line = new LinkedHashMap<>();
                line.put("rollNo", student.getRollNo());
                line.put("name", student.getName());
                line.put("branch", student.getBranch());
                line.put("batch", student.getBatch());
                line.put("missing", String.valueOf(missing));
                result.getPreview().add(line);
            }
            processed++;
        }

        if (processed == 0) {
            result.getNotes().add("No data rows found in the file.");
        }
        if (rerouted > 0) {
            result.getNotes().add(rerouted + " cell(s) were re-aligned to the correct Branch/Batch column.");
        }
        if (result.getMissingCells() > 0) {
            result.getNotes().add(result.getMissingCells() + " missing cell(s) were stored as null.");
        }
        // Every imported student (with a roll number) can now sign in immediately.
        int accounts = userAccountService.ensureStudentAccounts();
        if (accounts > 0) {
            result.getNotes().add(accounts + " student login account(s) created (roll no = initial password).");
        }
        return result;
    }

    // ---------------------------------------------------------------------
    // Row -> field extraction
    // ---------------------------------------------------------------------

    /** Strict positional mapping used for Excel/CSV (cells are already atomic). */
    private Map<String, String> extractPositional(String[] cells, Map<String, Integer> mapping) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String field : new String[]{F_ROLL, F_NAME, F_BRANCH, F_BATCH}) {
            Integer idx = mapping.get(field);
            fields.put(field, (idx == null || idx >= cells.length) ? null : cells[idx]);
        }
        return fields;
    }

    /**
     * Token balancing used for PDF rows: a cell such as "akshay kumar" may arrive as
     * several tokens, so surplus tokens are merged into the name column; short rows
     * keep their leading columns and the rest becomes null.
     */
    private Map<String, String> extractByTokens(String[] cells, Map<String, Integer> mapping,
                                                int expected, int nameIdx) {
        List<String> tokens = new ArrayList<>();
        for (String cell : cells) {
            if (cell == null) continue;
            for (String part : cell.trim().split("\\s+")) {
                if (!part.isEmpty()) tokens.add(part);
            }
        }

        List<String> columns = new ArrayList<>();
        int absorbed = tokens.size() - expected;
        if (absorbed <= 0) {
            columns.addAll(tokens);
        } else {
            int nameAt = Math.min(nameIdx, Math.max(0, tokens.size() - 1));
            for (int i = 0; i < nameAt && i < tokens.size(); i++) columns.add(tokens.get(i));
            StringBuilder merged = new StringBuilder();
            for (int i = nameAt; i <= nameAt + absorbed && i < tokens.size(); i++) {
                if (merged.length() > 0) merged.append(' ');
                merged.append(tokens.get(i));
            }
            columns.add(merged.toString());
            for (int i = nameAt + absorbed + 1; i < tokens.size(); i++) columns.add(tokens.get(i));
        }

        Map<String, String> fields = new LinkedHashMap<>();
        for (String field : new String[]{F_ROLL, F_NAME, F_BRANCH, F_BATCH}) {
            Integer idx = mapping.get(field);
            fields.put(field, (idx == null || idx >= columns.size()) ? null : columns.get(idx));
        }
        return fields;
    }

    /**
     * A column headed "course" is ambiguous: values like "CSE"/"CEC" are
     * branches, values like "A".."G" (or an already registered batch) are
     * batches. Moves a non-batch value from batch to branch when branch is
     * still empty.
     *
     * @return true when a value was rerouted
     */
    private boolean routeCourseValue(Map<String, String> fields) {
        String batch = blankToNull(fields.get(F_BATCH));
        if (batch == null) return false;
        if (looksLikeBatch(batch)) return false;
        if (blankToNull(fields.get(F_BRANCH)) == null) {
            fields.put(F_BRANCH, batch);
            fields.put(F_BATCH, null);
            return true;
        }
        fields.put(F_BATCH, null);
        return true;
    }

    /** Batch-like = an already registered batch or a single letter (A..Z, custom H, ...). */
    private boolean looksLikeBatch(String value) {
        if (value == null) return true;
        String trimmed = value.trim();
        if (batchService.isKnown(trimmed)) return true;
        return trimmed.length() == 1 && Character.isLetter(trimmed.charAt(0));
    }

    /**
     * Cleans a batch value: blank and semester-like values (legacy files with a
     * "semester" column) become null, everything else is registered as a batch.
     */
    private String normaliseBatch(String value) {
        String trimmed = blankToNull(value);
        if (trimmed == null) return null;
        if (trimmed.matches("(?i)(sem[\\s-]*\\d{1,2}|s\\d{1,2}|\\d{1,2}(st|nd|rd|th)?)")) {
            return null;   // semester values are not part of the student model
        }
        return trimmed;
    }

    // ---------------------------------------------------------------------
    // Header mapping
    // ---------------------------------------------------------------------

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

    /** True when the row looks like a column header ("roll no", "name", ...). */
    private boolean looksLikeHeader(String[] cells) {
        if (mapHeader(cells).size() >= 2) return true;
        List<String> tokens = new ArrayList<>();
        for (String cell : cells) {
            if (cell == null) continue;
            for (String part : cell.trim().split("\\s+")) {
                if (!part.isEmpty()) tokens.add(part);
            }
        }
        return mapHeader(tokens.toArray(new String[0])).size() >= 3;
    }

    /** True when the header cell for a field literally says "course" (ambiguous). */
    private boolean headerCellIs(String[] headerCells, Integer index, String expected) {
        if (headerCells == null || index == null || index >= headerCells.length) return false;
        String raw = headerCells[index];
        if (raw == null) return false;
        return raw.trim().toLowerCase().replaceAll("[.:]", "").replaceAll("\\s+", " ").equals(expected);
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

    private String aliasFor(String raw) {
        if (raw == null) return null;
        String header = raw.trim().toLowerCase().replaceAll("[.:]", "").replaceAll("\\s+", " ");
        switch (header) {
            case "roll no": case "roll number": case "rollno": case "roll": case "roll #":
            case "enrollment no": case "enrollment number": case "enrolment no":
            case "student id": case "student roll no": case "id":
                return F_ROLL;
            case "name": case "student name": case "full name": case "student":
                return F_NAME;
            case "branch": case "dept": case "department": case "stream":
            case "program": case "programme":
                return F_BRANCH;
            case "batch": case "batch/course": case "batch / course": case "class":
            case "section": case "group": case "course":
                return F_BATCH;
            default:
                return null;
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

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

    private int countMissing(String... values) {
        int missing = 0;
        for (String value : values) if (value == null) missing++;
        return missing;
    }

    private Student newStudent(String roll, String name, String branch, String batch) {
        Student student = new Student();
        student.setRollNo(roll);
        student.setName(name);
        student.setBranch(branch);
        student.setBatch(batch);
        return student;
    }
}
