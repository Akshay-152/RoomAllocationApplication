package com.exam.allocation.controller;

import com.exam.allocation.service.ExamImportService;
import com.exam.allocation.service.ImportService;
import com.exam.allocation.service.RoomImportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * File upload endpoints:
 * <ul>
 *   <li>POST /api/import/students - student lists (Roll No, Name, Branch, Batch)</li>
 *   <li>POST /api/import/exams    - exam data (Subject, Branch, Batch, Date, Time)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/import")
public class ImportController {

    @Autowired
    private ImportService importService;

    @Autowired
    private ExamImportService examImportService;

    @Autowired
    private RoomImportService roomImportService;

    @PostMapping("/students")
    public ImportService.ImportResult importStudents(@RequestParam("file") MultipartFile file) throws IOException {
        return importService.importStudents(file);
    }

    @PostMapping("/exams")
    public ExamImportService.ExamImportResult importExams(@RequestParam("file") MultipartFile file) throws IOException {
        return examImportService.importExams(file);
    }

    @PostMapping("/rooms")
    public RoomImportService.RoomImportResult importRooms(@RequestParam("file") MultipartFile file) throws IOException {
        return roomImportService.importRooms(file);
    }
}
