package com.exam.allocation.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared reader that turns an uploaded PDF, Excel (.xls/.xlsx) or delimited
 * (CSV/TSV/TXT) file into a list of rows of cell strings.
 *
 * <p>Used by both the student import ({@link ImportService}) and the exam-data
 * import ({@link ExamImportService}) so file handling is not duplicated.
 * Blank rows are kept as empty arrays; missing cells are {@code null}.
 */
@Component
public class FileTableReader {

    /** True when the file extension is one of the supported formats. */
    public static boolean isSupported(String fileName) {
        String lower = lower(fileName);
        return lower.endsWith(".xlsx") || lower.endsWith(".xls") || lower.endsWith(".pdf")
                || lower.endsWith(".csv") || lower.endsWith(".txt") || lower.endsWith(".tsv");
    }

    /**
     * Reads the file into rows of cells.
     * @throws IllegalArgumentException for an empty file or unsupported format
     * @throws IOException for unreadable/corrupt files
     */
    public List<String[]> read(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("No file uploaded. Choose a .pdf, .xlsx, .xls or .csv file.");
        }
        String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String lower = lower(original);

        if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) {
            return readExcel(file);
        }
        if (lower.endsWith(".pdf")) {
            return readPdf(file);
        }
        if (lower.endsWith(".csv") || lower.endsWith(".txt") || lower.endsWith(".tsv")) {
            return readDelimited(file);
        }
        throw new IllegalArgumentException(
                "Unsupported file type \"" + original + "\". Supported formats: .pdf, .xlsx, .xls, .csv, .txt");
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase();
    }

    // ---------------------------------------------------------------------
    // Readers
    // ---------------------------------------------------------------------

    private List<String[]> readExcel(MultipartFile file) throws IOException {
        List<String[]> rows = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter();
            for (Row row : sheet) {
                short last = row.getLastCellNum();
                int width = Math.max(0, Math.min(last, 40));
                String[] cells = new String[width];
                for (int c = 0; c < width; c++) {
                    Cell cell = row.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                    String value = cell == null ? null : formatter.formatCellValue(cell).trim();
                    cells[c] = (value == null || value.isEmpty()) ? null : value;
                }
                rows.add(cells);
            }
        }
        return rows;
    }

    private List<String[]> readPdf(MultipartFile file) throws IOException {
        List<String[]> rows = new ArrayList<>();
        try (PDDocument document = PDDocument.load(file.getInputStream())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(document);
            for (String line : text.split("\\r\\n|\\n|\\r")) {
                if (line.trim().isEmpty()) {
                    rows.add(new String[0]);
                    continue;
                }
                rows.add(splitLoose(line));
            }
        }
        return rows;
    }

    private List<String[]> readDelimited(MultipartFile file) throws IOException {
        List<String[]> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String firstLine = reader.readLine();
            if (firstLine == null) return rows;
            char delimiter = detectDelimiter(firstLine);
            rows.add(splitDelimited(firstLine, delimiter));
            String line;
            while ((line = reader.readLine()) != null) {
                rows.add(splitDelimited(line, delimiter));
            }
        }
        return rows;
    }

    // ---------------------------------------------------------------------
    // Line splitting helpers
    // ---------------------------------------------------------------------

    /** Splits a PDF line into table cells on 2+ spaces, tabs or pipes. */
    private String[] splitLoose(String line) {
        String trimmed = line.trim();
        String[] parts = trimmed.split("\\s{2,}|\\t|\\|");
        List<String> cells = new ArrayList<>();
        for (String part : parts) {
            String value = part.trim();
            if (!value.isEmpty()) cells.add(value);
        }
        return cells.toArray(new String[0]);
    }

    private char detectDelimiter(String line) {
        if (line.contains("\t")) return '\t';
        if (line.contains(";")) return ';';
        return ',';
    }

    private String[] splitDelimited(String line, char delimiter) {
        List<String> cells = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') { current.append('"'); i++; }
                    else inQuotes = false;
                } else current.append(c);
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == delimiter) {
                cells.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        cells.add(current.toString());
        return cells.toArray(new String[0]);
    }
}
