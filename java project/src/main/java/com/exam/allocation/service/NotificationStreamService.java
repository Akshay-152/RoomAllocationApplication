package com.exam.allocation.service;

import com.exam.allocation.model.Notification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Streams committed notifications to student portals that are currently open. */
@Service
public class NotificationStreamService {

    private static final Logger log = LoggerFactory.getLogger(NotificationStreamService.class);

    private final Map<Long, Set<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    public SseEmitter subscribe(Long studentId) {
        SseEmitter emitter = new SseEmitter(0L);
        Set<SseEmitter> studentSubscribers =
                subscribers.computeIfAbsent(studentId, ignored -> ConcurrentHashMap.newKeySet());
        studentSubscribers.add(emitter);
        emitter.onCompletion(() -> remove(studentId, emitter));
        emitter.onTimeout(() -> remove(studentId, emitter));
        emitter.onError(error -> remove(studentId, emitter));
        try {
            emitter.send(SseEmitter.event().name("ready").data(Map.of("connected", true)));
        } catch (IOException | IllegalStateException ex) {
            remove(studentId, emitter);
        }
        return emitter;
    }

    /**
     * Sends only after the database transaction commits, so clients never see
     * an event that was rolled back.
     */
    public void publish(Notification notification) {
        Runnable dispatch = () -> broadcast(notification);
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatch.run();
                }
            });
        } else {
            dispatch.run();
        }
    }

    @Scheduled(fixedRate = 20000)
    public void heartbeat() {
        subscribers.forEach((studentId, emitters) -> emitters.forEach(emitter -> {
            try {
                emitter.send(SseEmitter.event().comment("keepalive"));
            } catch (IOException | IllegalStateException ex) {
                remove(studentId, emitter);
            }
        }));
    }

    private void broadcast(Notification notification) {
        Set<SseEmitter> emitters = subscribers.get(notification.getStudentId());
        if (emitters == null || emitters.isEmpty()) return;
        Map<String, Object> payload = payload(notification);
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("notification")
                        .id(notification.getId() == null ? null : notification.getId().toString())
                        .data(payload));
            } catch (IOException | IllegalStateException ex) {
                log.debug("Removing disconnected notification stream for student {}: {}",
                        notification.getStudentId(), ex.getMessage());
                remove(notification.getStudentId(), emitter);
            }
        }
    }

    private void remove(Long studentId, SseEmitter emitter) {
        subscribers.computeIfPresent(studentId, (id, emitters) -> {
            emitters.remove(emitter);
            return emitters.isEmpty() ? null : emitters;
        });
    }

    private Map<String, Object> payload(Notification notification) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", notification.getId());
        out.put("type", notification.getType() == null ? null : notification.getType().name());
        out.put("title", notification.getTitle());
        out.put("body", notification.getBody());
        out.put("link", notification.getLink());
        out.put("examId", notification.getExamId());
        out.put("read", notification.isRead());
        out.put("createdAt", notification.getCreatedAt() == null
                ? null : notification.getCreatedAt().toString());
        return out;
    }
}
