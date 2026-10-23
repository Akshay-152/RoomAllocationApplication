package com.exam.allocation.controller;

import com.exam.allocation.model.Batch;
import com.exam.allocation.service.BatchService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Course/batch management endpoints.
 *
 * <ul>
 *   <li>GET    /api/batches        - list all batches (defaults A..G first)</li>
 *   <li>POST   /api/batches        - "Add Course": create a custom batch</li>
 *   <li>DELETE /api/batches/{id}   - remove a batch (blocked while students use it)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/batches")
public class BatchController {

    @Autowired
    private BatchService batchService;

    @GetMapping
    public List<Batch> getAllBatches() {
        return batchService.findAll();
    }

    @PostMapping
    public Batch addBatch(@RequestBody Map<String, String> body) {
        return batchService.create(body.get("name"));
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> deleteBatch(@PathVariable Long id) {
        batchService.delete(id);
        return Map.of("deleted", true);
    }
}
