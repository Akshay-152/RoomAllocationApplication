package com.exam.allocation.service;

import com.exam.allocation.model.AuditLog;
import com.exam.allocation.repository.AuditLogRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/** Records sensitive administrative actions in the {@code audit_logs} table. */
@Service
public class AuditService {

    @Autowired private AuditLogRepository auditLogRepository;

    public void record(String actor, String action, String target, String details) {
        String safeDetails = details == null ? null : (details.length() > 990 ? details.substring(0, 990) : details);
        auditLogRepository.save(new AuditLog(actor == null ? "system" : actor, action, target, safeDetails));
    }

    public List<AuditLog> recent() {
        return auditLogRepository.findTop100ByOrderByTimestampDesc();
    }
}
