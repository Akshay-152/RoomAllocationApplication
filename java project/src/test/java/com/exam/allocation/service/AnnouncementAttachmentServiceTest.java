package com.exam.allocation.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnnouncementAttachmentServiceTest {

    @TempDir
    Path storageDirectory;

    @Test
    void storesPdfOutsideThePublicDirectoryAndUsesASafeGeneratedKey() throws IOException {
        AnnouncementAttachmentService service =
                new AnnouncementAttachmentService(storageDirectory.toString());
        byte[] pdf = "%PDF-1.4\nprivate".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        MockMultipartFile upload = new MockMultipartFile(
                "file", "C:\\fakepath\\admit card.pdf", "application/pdf", pdf);

        AnnouncementAttachmentService.StoredAttachment stored = service.store(upload);

        assertEquals("admit_card.pdf", stored.fileName());
        assertTrue(stored.storageKey().matches(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"));
        assertArrayEquals(pdf, service.read(stored.storageKey()));
        assertTrue(Files.exists(storageDirectory.resolve(stored.storageKey() + ".pdf")));
        service.delete(stored.storageKey());
        assertFalse(Files.exists(storageDirectory.resolve(stored.storageKey() + ".pdf")));
    }

    @Test
    void rejectsNonPdfFilesAndPdfExtensionsWithInvalidContent() {
        AnnouncementAttachmentService service =
                new AnnouncementAttachmentService(storageDirectory.toString());

        assertThrows(IllegalArgumentException.class, () -> service.store(
                new MockMultipartFile("file", "notice.txt", "text/plain", "not a PDF".getBytes())));
        assertThrows(IllegalArgumentException.class, () -> service.store(
                new MockMultipartFile("file", "notice.pdf", "application/pdf", "not a PDF".getBytes())));
    }

    @Test
    void rejectsTraversalKeys() {
        AnnouncementAttachmentService service =
                new AnnouncementAttachmentService(storageDirectory.toString());

        assertThrows(IllegalArgumentException.class, () -> service.read("../secret"));
    }
}
