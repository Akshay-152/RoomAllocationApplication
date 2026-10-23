package com.exam.allocation.controller;

import com.exam.allocation.service.AuditService;
import com.exam.allocation.service.SessionService;
import com.exam.allocation.service.UserAccountService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Sign-in support endpoints: CSRF token, current account, self-service password change. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired private SessionService sessionService;
    @Autowired private UserAccountService userAccountService;
    @Autowired private AuditService auditService;

    /** Returns the CSRF token; Spring Security also writes it to the XSRF-TOKEN cookie. */
    @GetMapping("/csrf")
    public Map<String, String> csrf(HttpServletRequest request) throws Exception {
        Object token = request.getAttribute("org.springframework.security.web.csrf.CsrfToken");
        if (token == null) token = request.getAttribute("_csrf");
        String value = token == null ? null : ((org.springframework.security.web.csrf.CsrfToken) token).getToken();
        return Map.of("token", value == null ? "" : value);
    }

    /** The signed-in account, used by the SPA to restore the session after a reload. */
    @GetMapping("/me")
    public Map<String, Object> me() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return Map.of();
        }
        // Described inside a JPA session by SessionService (lazy student association).
        return sessionService.describe(auth.getName());
    }

    /** Changes the signed-in user's password after verifying the current one. */
    @PostMapping("/change-password")
    public Map<String, Object> changePassword(@RequestBody Map<String, String> body) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String username = auth.getName();
        userAccountService.changePassword(username, body.get("currentPassword"), body.get("newPassword"));
        auditService.record(username, "PASSWORD_CHANGE", "user:" + username, "Password changed by the account owner.");
        return Map.of("changed", true, "message", "Your password has been updated.");
    }
}
