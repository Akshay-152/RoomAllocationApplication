package com.exam.allocation.service;

import com.exam.allocation.model.Batch;
import com.exam.allocation.repository.BatchRepository;
import com.exam.allocation.repository.StudentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Course/batch management.
 *
 * <p>Ships with the default batches A, B, C, D, E, F, G (seeded on startup)
 * and lets the user register additional custom batches ("Add Course" button).
 * Every batch registered here is immediately available in the student form,
 * the exam form and therefore in the timetable/seating allocation.
 */
@Service
public class BatchService implements ApplicationRunner {

    /** Default batch/course options required by the specification. */
    public static final List<String> DEFAULT_BATCHES = List.of("A", "B", "C", "D", "E", "F", "G");

    @Autowired
    private BatchRepository batchRepository;

    @Autowired
    private StudentRepository studentRepository;

    /** Seeds the default batches A..G when the table is empty (runs at startup). */
    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (batchRepository.count() == 0) {
            for (String name : DEFAULT_BATCHES) {
                batchRepository.save(new Batch(name, false));
            }
        }
    }

    /** All batches, defaults first then alphabetically (for dropdowns and the Courses tab). */
    @Transactional
    public List<Batch> findAll() {
        ensureSeeded();
        List<Batch> batches = new ArrayList<>(batchRepository.findAll());
        batches.sort(Comparator.comparing((Batch b) -> !b.isCustom())
                .thenComparing(Batch::getName, String.CASE_INSENSITIVE_ORDER));
        return batches;
    }

    public Batch findByName(String name) {
        return name == null ? null : batchRepository.findByNameIgnoreCase(name.trim());
    }

    public boolean isKnown(String name) {
        return findByName(name) != null;
    }

    /**
     * Registers a new custom batch/course.
     * @throws IllegalArgumentException when the name is blank or already exists
     */
    @Transactional
    public Batch create(String name) {
        String cleaned = name == null ? "" : name.trim();
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("Batch name must not be blank.");
        }
        if (cleaned.length() > 40) {
            throw new IllegalArgumentException("Batch name is too long (max 40 characters).");
        }
        if (isKnown(cleaned)) {
            throw new IllegalArgumentException("Batch \"" + cleaned + "\" already exists.");
        }
        return batchRepository.save(new Batch(cleaned, true));
    }

    /**
     * Returns the batch with this name, creating it as a custom batch when it
     * does not exist yet. Used by imports so that batches found in uploaded
     * files become available to the timetable system automatically.
     */
    @Transactional
    public Batch ensureExists(String name) {
        String cleaned = name == null ? "" : name.trim();
        if (cleaned.isEmpty()) return null;
        Batch existing = batchRepository.findByNameIgnoreCase(cleaned);
        if (existing != null) return existing;
        return batchRepository.save(new Batch(cleaned, true));
    }

    /**
     * Deletes a batch, unless students are still assigned to it.
     * @throws IllegalArgumentException when the batch is in use or unknown
     */
    @Transactional
    public void delete(Long id) {
        Batch batch = batchRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Batch not found with id " + id));
        long inUse = studentRepository.countByBatch(batch.getName());
        if (inUse > 0) {
            throw new IllegalArgumentException("Cannot delete \"" + batch.getName() + "\": "
                    + inUse + " student(s) still belong to this batch. Reassign them first.");
        }
        batchRepository.delete(batch);
    }

    /** Makes sure defaults exist even if the startup runner has not fired yet. */
    private void ensureSeeded() {
        if (batchRepository.count() == 0) {
            run(null);
        }
    }
}
