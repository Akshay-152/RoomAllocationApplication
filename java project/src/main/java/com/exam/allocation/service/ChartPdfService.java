package com.exam.allocation.service;

import com.exam.allocation.model.Exam;
import com.exam.allocation.model.Seat;
import com.exam.allocation.model.Student;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders the seating chart for an exam as a landscape A4 PDF:
 * one section per room drawn as a grid of seats, colour-coded by course.
 */
@Service
public class ChartPdfService {

    private static final float PAGE_W = PDRectangle.A4.getHeight(); // landscape A4
    private static final float PAGE_H = PDRectangle.A4.getWidth();
    private static final float MARGIN = 36f;
    private static final float BOTTOM = 46f;
    private static final float ROW_LABEL_W = 24f;
    private static final float COL_LABEL_H = 14f;
    private static final float CELL_H = 42f;

    private static final int[][] PALETTE = {
        {79, 70, 229},   // indigo
        {16, 185, 129},  // green
        {245, 158, 11},  // amber
        {239, 68, 68},   // red
        {14, 165, 233},  // sky
        {168, 85, 247},  // purple
        {236, 72, 153},  // pink
        {20, 184, 166},  // teal
    };

    /** Drawing state: current page stream and y position. */
    private static class Cursor {
        final PDDocument doc;
        PDPageContentStream cs;
        float y;

        Cursor(PDDocument doc) { this.doc = doc; }
    }

    public byte[] buildChartPdf(Exam exam, List<Seat> seats) throws IOException {
        try (PDDocument document = new PDDocument()) {
            Cursor cursor = new Cursor(document);
            newPage(cursor);

            drawHeader(cursor, exam);
            Map<String, Integer[]> courses = courseColors(seats);
            drawLegend(cursor, courses);

            Map<Long, List<Seat>> byRoom = new LinkedHashMap<>();
            for (Seat seat : seats) {
                Long roomId = seat.getRoom() == null ? -1L : seat.getRoom().getId();
                byRoom.computeIfAbsent(roomId, k -> new ArrayList<>()).add(seat);
            }
            for (List<Seat> roomSeats : byRoom.values()) {
                roomSeats.sort(Comparator.comparingInt((Seat s) -> s.getRowNumber())
                        .thenComparingInt(s -> s.getColumnNumber()));
                drawRoom(cursor, roomSeats, courses);
            }

            drawRoster(cursor, exam, seats);

            cursor.cs.close();
            drawFooters(document);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    // ------------------------------------------------------------------ pages

    private void newPage(Cursor cursor) throws IOException {
        PDPage page = new PDPage(new PDRectangle(PAGE_W, PAGE_H));
        cursor.doc.addPage(page);
        cursor.cs = new PDPageContentStream(cursor.doc, page);
        cursor.y = PAGE_H - MARGIN;
    }

    /** Starts a fresh page when height no longer fits; returns true when that happened. */
    private boolean ensureSpace(Cursor cursor, float height) throws IOException {
        if (cursor.y - height >= BOTTOM) return false;
        cursor.cs.close();
        newPage(cursor);
        return true;
    }

    // ------------------------------------------------------------------ blocks

    private void drawHeader(Cursor cursor, Exam exam) throws IOException {
        PDPageContentStream cs = cursor.cs;
        centered(cs, cursor.y - 4, "SEATING CHART", PDType1Font.HELVETICA_BOLD, 18);
        cursor.y -= 24;
        centered(cs, cursor.y, value(exam.getSubjectName(), "Exam"), PDType1Font.HELVETICA_BOLD, 13);
        cursor.y -= 18;
        String semesterLabel = "All";
        if (exam.getSemesters() != null && !exam.getSemesters().isEmpty()) {
            java.util.List<Integer> sems = new java.util.ArrayList<>(exam.getSemesters());
            java.util.Collections.sort(sems);
            semesterLabel = sems.toString().replace("[", "").replace("]", "").replace(" ", "");
        } else if (exam.getSemester() != null) {
            semesterLabel = String.valueOf(exam.getSemester());
        }
        String meta = "Branch: " + value(exam.getBranch(), "All")
                + "     Batch: " + value(exam.getCourse(), "All")
                + "     Semester: " + semesterLabel
                + "     Date: " + value(exam.getExamDate() == null ? null : exam.getExamDate().toString(), "-")
                + "     Time: " + value(exam.getExamTime() == null ? null : exam.getExamTime().toString(), "-")
                + "     Application ID: " + value(exam.getApplicationId(), "-");
        centered(cs, cursor.y, meta, PDType1Font.HELVETICA, 9);
        cursor.y -= 13;
        centered(cs, cursor.y, "Generated: " + LocalDate.now() + "  "
                + LocalTime.now().withNano(0).format(DateTimeFormatter.ofPattern("HH:mm")),
                PDType1Font.HELVETICA, 8);
        cursor.y -= 10;

        strokeRgb(cs, 79, 70, 229);
        cs.setLineWidth(1.4f);
        cs.moveTo(MARGIN, cursor.y);
        cs.lineTo(PAGE_W - MARGIN, cursor.y);
        cs.stroke();
        strokeRgb(cs, 0, 0, 0);
        cursor.y -= 22;
    }

    private void drawLegend(Cursor cursor, Map<String, Integer[]> courses) throws IOException {
        if (courses.isEmpty()) return;
        ensureSpace(cursor, 20);
        PDPageContentStream cs = cursor.cs;
        cs.beginText();
        cs.setFont(PDType1Font.HELVETICA_BOLD, 8);
        cs.newLineAtOffset(MARGIN, cursor.y);
        cs.showText("Branch \u00b7 Batch:");
        cs.endText();

        float x = MARGIN + 84;
        for (Map.Entry<String, Integer[]> entry : courses.entrySet()) {
            Integer[] rgb = entry.getValue();
            fillRgb(cs, rgb[0], rgb[1], rgb[2]);
            cs.addRect(x, cursor.y - 1, 8, 8);
            cs.fill();
            fillRgb(cs, 30, 30, 30);
            String label = entry.getKey() + " (" + rgb[3] + ")";
            cs.beginText();
            cs.setFont(PDType1Font.HELVETICA, 8);
            cs.newLineAtOffset(x + 11, cursor.y - 1);
            cs.showText(sanitize(label));
            cs.endText();
            x += 18 + label.length() * 4.3f;
            if (x > PAGE_W - MARGIN - 80) break;
        }
        cursor.y -= 20;
    }

    private void drawRoom(Cursor cursor, List<Seat> roomSeats, Map<String, Integer[]> courses) throws IOException {
        if (roomSeats.isEmpty()) return;
        Seat first = roomSeats.get(0);
        int rows = roomSeats.get(roomSeats.size() - 1).getRowNumber();
        int cols = roomSeats.stream().mapToInt(s -> s.getColumnNumber()).max().orElse(1);
        String roomNumber = first.getRoom() == null ? "?" : first.getRoom().getRoomNumber();

        // Group seats by desk cell (row:col), ordered by desk position.
        Map<String, List<Seat>> cells = new LinkedHashMap<>();
        for (Seat seat : roomSeats) {
            cells.computeIfAbsent(seat.getRowNumber() + ":" + seat.getColumnNumber(),
                    k -> new ArrayList<>()).add(seat);
        }
        int perDesk = cells.values().stream().mapToInt(l -> l.size()).max().orElse(1);
        float cellH = perDesk <= 1 ? CELL_H : Math.max(50f, 16 + perDesk * 18);

        ensureSpace(cursor, cellH + COL_LABEL_H + 40);
        cursor.y -= 6;

        PDPageContentStream cs = cursor.cs;
        cs.beginText();
        cs.setFont(PDType1Font.HELVETICA_BOLD, 11);
        cs.newLineAtOffset(MARGIN, cursor.y);
        cs.showText(sanitize("Room " + roomNumber + "   (" + rows + " rows x " + cols
                + " columns, " + roomSeats.size() + " seats)"));
        cs.endText();
        cursor.y -= 16;

        float gridW = PAGE_W - 2 * MARGIN - ROW_LABEL_W;
        float cellW = Math.min(130f, Math.max(20f, gridW / Math.max(1, cols)));
        float x0 = MARGIN + ROW_LABEL_W;

        drawColumnLabels(cursor, x0, cellW, cols);
        cursor.y -= COL_LABEL_H;

        for (int r = 1; r <= rows; r++) {
            if (ensureSpace(cursor, cellH)) {
                // Continued on a new page: repeat the column labels first.
                drawColumnLabels(cursor, x0, cellW, cols);
                cursor.y -= COL_LABEL_H;
            }
            drawRoomRow(cursor, r, cols, x0, cellW, cells, cellH, courses);
        }
        cursor.y -= 28;
    }

    private void drawColumnLabels(Cursor cursor, float x0, float cellW, int cols) throws IOException {
        PDPageContentStream cs = cursor.cs;
        for (int c = 1; c <= cols; c++) {
            centeredAt(cs, x0 + (c - 0.5f) * cellW, cursor.y - 9,
                    "C" + c, PDType1Font.HELVETICA, 7);
        }
    }

    private void drawRoomRow(Cursor cursor, int row, int cols, float x0, float cellW,
                             Map<String, List<Seat>> cells, float cellH,
                             Map<String, Integer[]> courses) throws IOException {
        PDPageContentStream cs = cursor.cs;
        float rowTop = cursor.y;
        float rowBottom = rowTop - cellH;

        cs.beginText();
        cs.setFont(PDType1Font.HELVETICA_BOLD, 8);
        cs.newLineAtOffset(MARGIN, rowTop - cellH / 2 - 3);
        cs.showText("R" + row);
        cs.endText();

        for (int c = 1; c <= cols; c++) {
            List<Seat> cellSeats = cells.getOrDefault(row + ":" + c, java.util.Collections.emptyList());
            drawSeat(cursor, x0 + (c - 1) * cellW, rowBottom, cellW, cellH, cellSeats, courses);
        }

        strokeRgb(cs, 209, 213, 219);
        cs.setLineWidth(0.6f);
        cs.moveTo(x0, rowBottom);
        cs.lineTo(x0 + cols * cellW, rowBottom);
        cs.stroke();
        strokeRgb(cs, 0, 0, 0);

        cursor.y = rowBottom;
    }

    /** Draws one desk cell: a single student (course-coloured) or N slots for a shared desk. */
    private void drawSeat(Cursor cursor, float x, float y, float w, float h,
                          List<Seat> cellSeats, Map<String, Integer[]> courses) throws IOException {
        PDPageContentStream cs = cursor.cs;
        boolean single = cellSeats.size() <= 1;
        Seat firstSeat = cellSeats.isEmpty() ? null : cellSeats.get(0);
        Student singleStudent = firstSeat == null ? null : firstSeat.getAllocatedStudent();

        if (single) {
            int[] rgb = singleStudent == null ? new int[]{243, 244, 246}
                    : colorFor(singleStudent, courses);
            fillRgb(cs, rgb[0], rgb[1], rgb[2]);
        } else {
            fillRgb(cs, 255, 255, 255);
        }
        cs.addRect(x, y, w, h);
        cs.fill();
        strokeRgb(cs, 156, 163, 175);
        cs.setLineWidth(0.6f);
        cs.addRect(x, y, w, h);
        cs.stroke();
        strokeRgb(cs, 0, 0, 0);

        if (single) {
            if (singleStudent == null) {
                fillRgb(cs, 156, 163, 175);   // visible grey on the light cell background
                centeredAt(cs, x + w / 2, y + h / 2 - 3, "VACANT", PDType1Font.HELVETICA, 7);
                return;
            }
            int[] rgb = colorFor(singleStudent, courses);
            boolean dark = rgb[0] + rgb[1] + rgb[2] < 400;
            fillRgb(cs, dark ? 255 : 17, dark ? 255 : 24, dark ? 255 : 39);
            centeredAt(cs, x + w / 2, y + h - 15,
                    fit(singleStudent.getRollNo(), (int) Math.max(4, w / 5.5)), PDType1Font.HELVETICA_BOLD, 9);
            centeredAt(cs, x + w / 2, y + h - 27,
                    fit(singleStudent.getName(), (int) Math.max(4, w / 4.6)), PDType1Font.HELVETICA, 7.5f);
            centeredAt(cs, x + w / 2, y + 6,
                    fit(groupLabel(singleStudent), (int) Math.max(4, w / 4.0)), PDType1Font.HELVETICA, 6.5f);
            return;
        }

        // Shared desk: one slot per position, top slot first.
        int n = cellSeats.size();
        float pad = 3;
        float slotH = (h - pad * 2) / n;
        for (int i = 0; i < n; i++) {
            Seat seat = cellSeats.get(i);
            float innerY = y + h - pad - (i + 1) * slotH + 1.5f;
            float innerH = slotH - 3;
            Student student = seat == null ? null : seat.getAllocatedStudent();

            if (student == null) {
                fillRgb(cs, 249, 250, 251);
                cs.addRect(x + 2, innerY, w - 4, innerH);
                cs.fill();
                fillRgb(cs, 156, 163, 175);   // visible grey on the light slot background
                centeredAt(cs, x + w / 2, innerY + innerH / 2 - 2.5f, "Vacant", PDType1Font.HELVETICA, 6.5f);
                continue;
            }

            int[] rgb = colorFor(student, courses);
            fillRgb(cs, rgb[0], rgb[1], rgb[2]);
            cs.addRect(x + 2, innerY, 3, innerH);   // course-coloured position bar
            cs.fill();

            fillRgb(cs, 17, 24, 39);
            String roll = sanitize(fit(student.getRollNo(), 8));
            float rollW;
            try {
                rollW = PDType1Font.HELVETICA_BOLD.getStringWidth(roll) / 1000f * 7.5f;
            } catch (IOException e) {
                rollW = roll.length() * 4f;
            }
            float textY = innerY + innerH / 2 - 2.5f;
            cs.beginText();
            cs.setFont(PDType1Font.HELVETICA_BOLD, 7.5f);
            cs.newLineAtOffset(x + 8, textY);
            cs.showText(roll);
            cs.endText();

            // Branch \u00b7 Batch of this student, right-aligned in the slot so every
            // desk clearly shows which group each of its students belongs to.
            String course = sanitize(fit(groupLabel(student), 8));
            float courseW;
            try {
                courseW = PDType1Font.HELVETICA.getStringWidth(course) / 1000f * 6f;
            } catch (IOException e) {
                courseW = course.length() * 3f;
            }
            float courseX = x + w - 5 - courseW;

            float nameX = x + 8 + rollW + 4;
            int nameChars = Math.max(2, (int) ((courseX - 4 - nameX) / 3.2f));
            cs.beginText();
            cs.setFont(PDType1Font.HELVETICA, 6.5f);
            cs.newLineAtOffset(nameX, textY);
            cs.showText(sanitize(fit(student.getName(), nameChars)));
            cs.endText();

            fillRgb(cs, 107, 114, 128);
            cs.beginText();
            cs.setFont(PDType1Font.HELVETICA, 6f);
            cs.newLineAtOffset(courseX, textY);
            cs.showText(course);
            cs.endText();
        }
    }

    // -------------------------------------------------- student roster table

    /** Column widths of the roster table (fits the landscape A4 text area). */
    private static final float[] ROSTER_W = {64f, 210f, 110f, 110f, 90f, 96f};
    private static final String[] ROSTER_HEAD = {
            "Roll No", "Name", "Branch", "Batch", "Room", "Seat"};
    private static final float ROSTER_ROW_H = 15f;

    /**
     * Draws the "Student List" table required by the specification:
     * Roll Number | Name | Branch | Batch/Course (+ room/seat of the assignment)
     * for every student seated in this exam.
     */
    private void drawRoster(Cursor cursor, Exam exam, List<Seat> seats) throws IOException {
        List<Seat> seated = new ArrayList<>();
        for (Seat seat : seats) {
            if (seat.getAllocatedStudent() != null) seated.add(seat);
        }
        if (seated.isEmpty()) return;

        seated.sort(Comparator.comparing((Seat s) -> AllocationService.groupKey(s.getAllocatedStudent()))
                .thenComparing(ChartPdfService::rollSortKey));

        ensureSpace(cursor, 90);
        cursor.y -= 16;

        PDPageContentStream cs = cursor.cs;
        strokeRgb(cs, 79, 70, 229);
        cs.setLineWidth(1.2f);
        cs.moveTo(MARGIN, cursor.y);
        cs.lineTo(PAGE_W - MARGIN, cursor.y);
        cs.stroke();
        strokeRgb(cs, 0, 0, 0);
        cursor.y -= 20;

        centered(cs, cursor.y, "STUDENT LIST", PDType1Font.HELVETICA_BOLD, 14);
        cursor.y -= 17;
        String sub = value(exam.getSubjectName(), "Exam")
                + "   -   " + value(exam.getExamDate() == null ? null : exam.getExamDate().toString(), "-")
                + "   " + value(exam.getExamTime() == null ? null : exam.getExamTime().toString(), "-")
                + "   -   " + seated.size() + " student(s)";
        centered(cs, cursor.y, sub, PDType1Font.HELVETICA, 9);
        cursor.y -= 18;

        float[] xs = rosterXs();
        drawRosterHeader(cursor, xs);
        cursor.y -= ROSTER_ROW_H;

        int i = 0;
        for (Seat seat : seated) {
            if (ensureSpace(cursor, ROSTER_ROW_H)) {
                drawRosterHeader(cursor, xs);
                cursor.y -= ROSTER_ROW_H;
            }
            drawRosterRow(cursor, xs, rosterValues(seat), i % 2 == 1);
            cursor.y -= ROSTER_ROW_H;
            i++;
        }
        cursor.y -= 8;
    }

    private float[] rosterXs() {
        float[] xs = new float[ROSTER_W.length];
        float x = MARGIN;
        for (int i = 0; i < ROSTER_W.length; i++) {
            xs[i] = x;
            x += ROSTER_W[i];
        }
        return xs;
    }

    private void drawRosterHeader(Cursor cursor, float[] xs) throws IOException {
        PDPageContentStream cs = cursor.cs;
        float top = cursor.y;
        float bottom = top - ROSTER_ROW_H;
        fillRgb(cs, 79, 70, 229);
        cs.addRect(xs[0], bottom, totalRosterWidth(), ROSTER_ROW_H);
        cs.fill();
        fillRgb(cs, 255, 255, 255);
        for (int i = 0; i < ROSTER_HEAD.length; i++) {
            cs.beginText();
            cs.setFont(PDType1Font.HELVETICA_BOLD, 8);
            cs.newLineAtOffset(xs[i] + 5, bottom + 4.5f);
            cs.showText(sanitize(ROSTER_HEAD[i]));
            cs.endText();
        }
        fillRgb(cs, 0, 0, 0);
    }

    private void drawRosterRow(Cursor cursor, float[] xs, String[] values, boolean shaded)
            throws IOException {
        PDPageContentStream cs = cursor.cs;
        float top = cursor.y;
        float bottom = top - ROSTER_ROW_H;
        if (shaded) {
            fillRgb(cs, 249, 250, 251);
            cs.addRect(xs[0], bottom, totalRosterWidth(), ROSTER_ROW_H);
            cs.fill();
        }
        fillRgb(cs, 17, 24, 39);
        for (int i = 0; i < values.length && i < xs.length; i++) {
            int maxChars = Math.max(4, (int) (ROSTER_W[i] / 5.2f));
            cs.beginText();
            cs.setFont(i == 0 ? PDType1Font.HELVETICA_BOLD : PDType1Font.HELVETICA, 7.5f);
            cs.newLineAtOffset(xs[i] + 5, bottom + 4.5f);
            cs.showText(sanitize(fit(values[i], maxChars)));
            cs.endText();
        }
        strokeRgb(cs, 229, 231, 235);
        cs.setLineWidth(0.5f);
        cs.moveTo(xs[0], bottom);
        cs.lineTo(xs[0] + totalRosterWidth(), bottom);
        cs.stroke();
        strokeRgb(cs, 0, 0, 0);
    }

    private float totalRosterWidth() {
        float total = 0;
        for (float w : ROSTER_W) total += w;
        return total;
    }

    /** Roll No | Name | Branch | Batch | Room | Seat for one seated student. */
    private String[] rosterValues(Seat seat) {
        Student student = seat.getAllocatedStudent();
        String room = seat.getRoom() == null ? "-" : seat.getRoom().getRoomNumber();
        String seatRef = "R" + seat.getRowNumber() + " C" + seat.getColumnNumber();
        if (seat.getRoom() != null && seat.getRoom().getStudentsPerDesk() > 1) {
            seatRef += " P" + seat.getDeskPosition();
        }
        return new String[]{
                value(student.getRollNo(), "-"),
                value(student.getName(), "-"),
                value(student.getBranch(), "-"),
                value(student.getBatch(), "-"),
                room,
                seatRef
        };
    }

    /** Numeric-aware roll number sort key so "2" comes before "10". */
    private static String rollSortKey(Seat seat) {
        String roll = seat.getAllocatedStudent().getRollNo();
        if (roll == null || roll.isBlank()) return "\uffff";
        String trimmed = roll.trim();
        try {
            return String.format("%012d", Long.parseLong(trimmed));
        } catch (NumberFormatException ex) {
            return "\ufffe" + trimmed;
        }
    }

    private void drawFooters(PDDocument document) throws IOException {
        int total = document.getPages().getCount();
        String stamp = "Room Allocation System - generated " + LocalDate.now();
        for (int i = 0; i < total; i++) {
            PDPage page = document.getPage(i);
            try (PDPageContentStream cs = new PDPageContentStream(document, page, AppendMode.APPEND, true, true)) {
                fillRgb(cs, 107, 114, 128);
                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA, 7.5f);
                cs.newLineAtOffset(MARGIN, BOTTOM - 16);
                cs.showText(sanitize(stamp));
                cs.endText();
                String pageNo = "Page " + (i + 1) + " of " + total;
                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA, 7.5f);
                cs.newLineAtOffset(PAGE_W - MARGIN - pageNo.length() * 4.0f, BOTTOM - 16);
                cs.showText(pageNo);
                cs.endText();
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** course -> {r, g, b, allocatedCount} in first-seen order. */
    private Map<String, Integer[]> courseColors(List<Seat> seats) {
        Map<String, Integer[]> map = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Seat seat : seats) {
            if (seat.getAllocatedStudent() == null) continue;
            String course = AllocationService.groupKey(seat.getAllocatedStudent());
            counts.merge(course, 1, (a, b) -> a + b);
        }
        int i = 0;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            int[] rgb = PALETTE[i % PALETTE.length];
            map.put(entry.getKey(), new Integer[]{rgb[0], rgb[1], rgb[2], entry.getValue()});
            i++;
        }
        return map;
    }

    private int[] colorFor(Student student, Map<String, Integer[]> courses) {
        String key = AllocationService.groupKey(student);
        Integer[] rgb = courses.get(key);
        return rgb == null ? new int[]{79, 70, 229} : new int[]{rgb[0], rgb[1], rgb[2]};
    }

    /** Compact "CEC\u00b7E" label (branch\u00b7batch) used inside seat cells. */
    private String groupLabel(Student student) {
        String branch = (student.getBranch() == null || student.getBranch().isBlank())
                ? "-" : student.getBranch().trim();
        String batch = (student.getBatch() == null || student.getBatch().isBlank())
                ? "-" : student.getBatch().trim();
        return branch + "\u00b7" + batch;
    }

    private void centered(PDPageContentStream cs, float y, String text,
                          PDType1Font font, float size) throws IOException {
        centeredAt(cs, PAGE_W / 2, y, text, font, size);
    }

    private void centeredAt(PDPageContentStream cs, float cx, float y, String text,
                            PDType1Font font, float size) throws IOException {
        String safe = sanitize(text);
        float width;
        try {
            width = font.getStringWidth(safe) / 1000f * size;
        } catch (IOException e) {
            width = safe.length() * size * 0.5f;
        }
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(Math.max(MARGIN, cx - width / 2), y);
        cs.showText(safe);
        cs.endText();
    }

    private String fit(String text, int maxChars) {
        if (text == null) return "-";
        if (maxChars < 4) maxChars = 4;
        return text.length() <= maxChars ? text : text.substring(0, maxChars - 1) + "..";
    }

    /** Helvetica only supports Latin-1; replace anything outside it. */
    private String sanitize(String text) {
        if (text == null) return "-";
        StringBuilder sb = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            boolean ok = (c >= 32 && c < 127) || (c >= 160 && c <= 255);
            sb.append(ok ? c : '?');
        }
        return sb.toString();
    }

    private String value(String v, String fallback) {
        return (v == null || v.isBlank()) ? fallback : v;
    }

    /*
     * Colour helpers: the int (0..255) set*Color(...) overloads are deprecated in
     * PDFBox, so convert to the float (0..1) forms here instead of at every call site.
     */
    private static void strokeRgb(PDPageContentStream stream, int r, int g, int b) throws IOException {
        stream.setStrokingColor(r / 255f, g / 255f, b / 255f);
    }

    private static void fillRgb(PDPageContentStream stream, int r, int g, int b) throws IOException {
        stream.setNonStrokingColor(r / 255f, g / 255f, b / 255f);
    }
}
