package com.exam.allocation.controller;

import com.exam.allocation.model.Exam;
import com.exam.allocation.model.Room;
import com.exam.allocation.model.Seat;
import com.exam.allocation.repository.ExamRepository;
import com.exam.allocation.repository.SeatRepository;
import com.exam.allocation.service.ChartPdfService;
import com.exam.allocation.service.ExamSemesterService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Downloads the seating chart of an exam as a PDF file. */
@RestController
@RequestMapping("/api/chart")
public class ChartController {

    @Autowired
    private ExamRepository examRepository;

    @Autowired
    private SeatRepository seatRepository;

    @Autowired
    private ChartPdfService chartPdfService;

    @Autowired
    private ExamSemesterService examSemesterService;

    /**
     * The seating chart as a PDF.
     *
     * <p>Optional {@code roomId} and {@code batch} scope the download to one
     * room and/or one class (batch) for the concerned teacher's copy - the
     * layout and wording are identical to the full chart, only the content is
     * filtered (grid sections with no matching seat disappear; the roster keeps
     * Roll No and Seat for attendance). Without parameters the official full
     * chart is produced, exactly as before.
     */
    @GetMapping("/pdf")
    public ResponseEntity<byte[]> downloadChart(@RequestParam Long examId,
                                                @RequestParam(required = false) Long roomId,
                                                @RequestParam(required = false) String batch) throws IOException {
        Exam exam = examRepository.findById(examId)
                .orElseThrow(() -> new IllegalArgumentException("Exam not found with id " + examId));

        List<Seat> seats = seatRepository.findByExamId(examId);
        if (seats.isEmpty()) {
            throw new IllegalArgumentException(
                    "No seating chart exists for this exam yet. Generate the allocation first.");
        }

        StringBuilder scope = new StringBuilder();
        if (roomId != null) {
            Room room = seats.stream().map(Seat::getRoom).filter(Objects::nonNull)
                    .filter(r -> roomId.equals(r.getId()))
                    .findFirst().orElse(null);
            seats = seats.stream()
                    .filter(s -> s.getRoom() != null && roomId.equals(s.getRoom().getId()))
                    .toList();
            if (seats.isEmpty()) {
                throw new IllegalArgumentException(
                        "This exam's chart has no seats in the selected room.");
            }
            scope.append("-room-").append(sanitize(
                    room != null && room.getRoomNumber() != null
                            ? room.getRoomNumber() : String.valueOf(roomId)));
        }
        if (batch != null && !batch.isBlank()) {
            String wanted = batch.trim();
            seats = seats.stream()
                    .filter(s -> s.getAllocatedStudent() != null
                            && wanted.equalsIgnoreCase(s.getAllocatedStudent().getBatch()))
                    .toList();
            if (seats.isEmpty()) {
                throw new IllegalArgumentException(
                        "No seated students of class \"" + wanted + "\" in this exam's chart.");
            }
            scope.append("-class-").append(sanitize(wanted));
        }

        seats = new java.util.ArrayList<>(seats);
        seats.sort(Comparator
                .comparing((Seat s) -> s.getRoom() == null ? 0L : s.getRoom().getId())
                .thenComparingInt(s -> s.getRowNumber())
                .thenComparingInt(s -> s.getColumnNumber()));

        // Make the semester audience available to the PDF header (transient field).
        exam.setSemesters(examSemesterService.assignedSemesters(exam));

        byte[] pdf = chartPdfService.buildChartPdf(exam, seats);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + withScope(pdfFileName(exam), scope.toString()) + "\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    /** Inserts the scope suffix before the \".pdf\" extension so downloaded
     *  files keep a usable extension (\"...-room-201.pdf\", not \"...pdf-room-201\"). */
    private String withScope(String filename, String scope) {
        if (scope == null || scope.isEmpty()) return filename;
        int dot = filename.lastIndexOf('.');
        return dot >= 0
                ? filename.substring(0, dot) + scope + filename.substring(dot)
                : filename + scope;
    }

    private String pdfFileName(Exam exam) {
        StringBuilder name = new StringBuilder("seating-chart");
        String group = exam.getCourse() != null && !exam.getCourse().isBlank()
                ? exam.getCourse() : exam.getBranch();
        if (group != null && !group.isBlank()) {
            name.append('-').append(sanitize(group));
        }
        if (exam.getSubjectName() != null && !exam.getSubjectName().isBlank()) {
            name.append('-').append(sanitize(exam.getSubjectName()));
        }
        name.append('-').append(exam.getExamDate() != null ? exam.getExamDate() : LocalDate.now());
        return name + ".pdf";
    }

    private String sanitize(String value) {
        String cleaned = value.trim().replaceAll("[^A-Za-z0-9]+", "-");
        return cleaned.length() > 40 ? cleaned.substring(0, 40) : cleaned;
    }
}
