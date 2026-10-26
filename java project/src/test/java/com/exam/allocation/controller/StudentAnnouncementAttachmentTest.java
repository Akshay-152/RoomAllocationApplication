package com.exam.allocation.controller;

import com.exam.allocation.model.Announcement;
import com.exam.allocation.model.Student;
import com.exam.allocation.repository.AnnouncementRepository;
import com.exam.allocation.repository.ExamRepository;
import com.exam.allocation.repository.SeatAllocationRepository;
import com.exam.allocation.repository.StudentRepository;
import com.exam.allocation.service.AllocationService;
import com.exam.allocation.service.AnnouncementAttachmentService;
import com.exam.allocation.service.AuditService;
import com.exam.allocation.service.ExamSemesterService;
import com.exam.allocation.service.SessionService;
import com.exam.allocation.service.UserAccountService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StudentAnnouncementAttachmentTest {

    @Mock private SessionService sessionService;
    @Mock private StudentRepository studentRepository;
    @Mock private ExamRepository examRepository;
    @Mock private SeatAllocationRepository seatAllocationRepository;
    @Mock private AnnouncementRepository announcementRepository;
    @Mock private AllocationService allocationService;
    @Mock private ExamSemesterService examSemesterService;
    @Mock private UserAccountService userAccountService;
    @Mock private AuditService auditService;
    @Mock private AnnouncementAttachmentService announcementAttachmentService;

    @InjectMocks private StudentSelfController controller;

    @BeforeEach
    void signInAsStudent() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("student-101", null, List.of()));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void studentCanDownloadPdfWhenInAnnouncementAudience() throws Exception {
        Student student = student("E");
        Announcement announcement = announcement(7L, "E");
        byte[] pdf = "%PDF-1.4\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        when(sessionService.requireStudent("student-101")).thenReturn(student);
        when(announcementRepository.findById(7L)).thenReturn(Optional.of(announcement));
        when(announcementRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(announcement));
        when(announcementAttachmentService.read("stored-key")).thenReturn(pdf);

        var response = controller.downloadAnnouncementAttachment(7L);

        assertEquals(MediaType.APPLICATION_PDF, response.getHeaders().getContentType());
        assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
        assertArrayEquals(pdf, response.getBody());
    }

    @Test
    void studentOutsideAnnouncementAudienceCannotDownloadPdf() throws Exception {
        Student student = student("F");
        Announcement announcement = announcement(7L, "E");
        when(sessionService.requireStudent("student-101")).thenReturn(student);
        when(announcementRepository.findById(7L)).thenReturn(Optional.of(announcement));
        when(announcementRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(announcement));

        assertThrows(IllegalArgumentException.class,
                () -> controller.downloadAnnouncementAttachment(7L));
        verify(announcementAttachmentService, never()).read(anyString());
    }

    private Student student(String batch) {
        Student student = new Student();
        student.setId(101L);
        student.setRollNo("101");
        student.setBatch(batch);
        student.setAccountStatus(Student.AccountStatus.ACTIVE);
        return student;
    }

    private Announcement announcement(Long id, String batch) {
        Announcement announcement = new Announcement();
        announcement.setId(id);
        announcement.setTitle("Admit card released");
        announcement.setBatchName(batch);
        announcement.setAttachmentStorageKey("stored-key");
        announcement.setAttachmentFileName("admit_card.pdf");
        return announcement;
    }
}
