package com.exam.allocation.controller;

import com.exam.allocation.model.PushSubscription;
import com.exam.allocation.model.Student;
import com.exam.allocation.repository.PushSubscriptionRepository;
import com.exam.allocation.service.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationControllerTest {

    @Mock private PushSubscriptionRepository pushSubscriptionRepository;
    @Mock private SessionService sessionService;
    @Mock private HttpServletRequest request;

    @InjectMocks private NotificationController controller;

    /** Standard W3C Push API body the browser sends after subscribe(). */
    private Map<String, Object> registrationBody(String endpoint, String deviceInfo) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("subscription", Map.of(
                "endpoint", endpoint,
                "keys", Map.of("p256dh", "p256dh-key", "auth", "auth-key")));
        if (deviceInfo != null) body.put("deviceInfo", deviceInfo);
        return body;
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void savesTheBrowserSubscriptionForTheSignedInStudent() {
        Student student = student(41L);
        signInAs("student-41");
        when(sessionService.requireStudent("student-41")).thenReturn(student);
        when(pushSubscriptionRepository.findByEndpoint("https://push.example/sub-1"))
                .thenReturn(Optional.empty());
        when(pushSubscriptionRepository.save(any(PushSubscription.class)))
                .thenAnswer(invocation -> {
                    PushSubscription saved = invocation.getArgument(0);
                    saved.setId(7L);
                    return saved;
                });

        Map<String, Object> result = controller.register(
                registrationBody("https://push.example/sub-1", "Chrome on Windows"),
                request);

        ArgumentCaptor<PushSubscription> saved = ArgumentCaptor.forClass(PushSubscription.class);
        verify(pushSubscriptionRepository).save(saved.capture());
        assertEquals(41L, saved.getValue().getStudentId());
        assertEquals("https://push.example/sub-1", saved.getValue().getEndpoint());
        assertEquals("p256dh-key", saved.getValue().getP256dh());
        assertEquals("auth-key", saved.getValue().getAuth());
        assertEquals("Chrome on Windows", saved.getValue().getDeviceInfo());
        assertEquals(Boolean.TRUE, result.get("registered"));
    }

    @Test
    void reassignsARegisteredBrowserToTheCurrentStudent() {
        signInAs("student-42");
        when(sessionService.requireStudent("student-42")).thenReturn(student(42L));
        PushSubscription existing = new PushSubscription(
                41L, "https://push.example/shared", "p256dh-key", "auth-key", "Chrome");
        existing.setId(8L);
        when(pushSubscriptionRepository.findByEndpoint("https://push.example/shared"))
                .thenReturn(Optional.of(existing));
        when(pushSubscriptionRepository.save(existing)).thenReturn(existing);

        controller.register(registrationBody("https://push.example/shared", null), request);

        assertEquals(42L, existing.getStudentId());
        assertEquals("Chrome", existing.getDeviceInfo());
        verify(pushSubscriptionRepository).save(existing);
    }

    @Test
    void rejectsAnEmptyRegistrationBodyWithoutSavingASubscription() {
        signInAs("student-41");
        when(sessionService.requireStudent("student-41")).thenReturn(student(41L));

        assertThrows(IllegalArgumentException.class, () -> controller.register(null, request));

        verify(pushSubscriptionRepository, never()).save(any(PushSubscription.class));
    }

    @Test
    void rejectsASubscriptionWithoutKeys() {
        signInAs("student-41");
        when(sessionService.requireStudent("student-41")).thenReturn(student(41L));

        Map<String, Object> noKeys = Map.of(
                "subscription", Map.of("endpoint", "https://push.example/sub-2"));
        assertThrows(IllegalArgumentException.class,
                () -> controller.register(noKeys, request));

        verify(pushSubscriptionRepository, never()).save(any(PushSubscription.class));
    }

    private void signInAs(String username) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, "", List.of()));
    }

    private Student student(long id) {
        Student student = new Student();
        student.setId(id);
        return student;
    }
}
