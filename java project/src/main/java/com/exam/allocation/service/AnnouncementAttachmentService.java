package com.exam.allocation.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.UUID;

/** Stores announcement PDFs outside the public static directory. */
@Service
public class AnnouncementAttachmentService {

    private static final long MAX_FILE_SIZE = 10L * 1024 * 1024;
    private static final byte[] PDF_SIGNATURE = "%PDF-".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    public record StoredAttachment(String storageKey, String fileName) {}

    private final Path storageDirectory;

    public AnnouncementAttachmentService(
            @Value("${app.announcement-storage-dir:./data/announcement-attachments}") String storageDirectory) {
        this.storageDirectory = Path.of(storageDirectory).toAbsolutePath().normalize();
    }

    public StoredAttachment store(MultipartFile upload) throws IOException {
        if (upload == null || upload.isEmpty()) {
            throw new IllegalArgumentException("Choose a PDF attachment.");
        }
        if (upload.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("Announcement PDFs must be 10 MB or smaller.");
        }

        String fileName = safeFileName(upload.getOriginalFilename());
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw new IllegalArgumentException("Only PDF announcement attachments are allowed.");
        }

        byte[] content = upload.getBytes();
        if (content.length > MAX_FILE_SIZE || !hasPdfSignature(content)) {
            throw new IllegalArgumentException("The uploaded file is not a valid PDF.");
        }

        String storageKey = UUID.randomUUID().toString();
        Files.createDirectories(storageDirectory);
        Path destination = resolve(storageKey);
        try {
            Files.write(destination, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException ex) {
            try {
                Files.deleteIfExists(destination);
            } catch (IOException cleanupFailure) {
                ex.addSuppressed(cleanupFailure);
            }
            throw ex;
        }
        return new StoredAttachment(storageKey, fileName);
    }

    public byte[] read(String storageKey) throws IOException {
        return Files.readAllBytes(resolve(storageKey));
    }

    public void delete(String storageKey) throws IOException {
        Files.deleteIfExists(resolve(storageKey));
    }

    private Path resolve(String storageKey) {
        if (storageKey == null || !storageKey.matches(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw new IllegalArgumentException("Invalid announcement attachment key.");
        }
        Path resolved = storageDirectory.resolve(storageKey + ".pdf").normalize();
        if (!resolved.startsWith(storageDirectory)) {
            throw new IllegalArgumentException("Invalid announcement attachment key.");
        }
        return resolved;
    }

    private static String safeFileName(String originalFileName) {
        if (originalFileName == null || originalFileName.isBlank()) {
            throw new IllegalArgumentException("The PDF file needs a name.");
        }
        String baseName = originalFileName.replace('\\', '/');
        baseName = baseName.substring(baseName.lastIndexOf('/') + 1)
                .replaceAll("[^A-Za-z0-9._-]", "_");
        if (baseName.isBlank() || baseName.length() > 120) {
            throw new IllegalArgumentException("The PDF filename must be 120 characters or fewer.");
        }
        return baseName;
    }

    private static boolean hasPdfSignature(byte[] content) {
        if (content.length < PDF_SIGNATURE.length) return false;
        for (int i = 0; i < PDF_SIGNATURE.length; i++) {
            if (content[i] != PDF_SIGNATURE[i]) return false;
        }
        return true;
    }
}
