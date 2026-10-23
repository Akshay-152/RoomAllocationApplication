package com.exam.allocation.filter;

import com.exam.allocation.service.SessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Handles {@code POST /api/auth/login} with a JSON body and answers with the exact
 * validation messages required by the specification, without ever revealing a password.
 *
 * <p>Five failed attempts for the same IP + username inside 15 minutes are rejected
 * with HTTP 429 before the credentials are checked again.
 */
public class JsonLoginFilter extends OncePerRequestFilter {

    private static final int MAX_ATTEMPTS = 5;
    private static final Duration ATTEMPT_WINDOW = Duration.ofMinutes(15);

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final SessionService sessionService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AntPathRequestMatcher loginMatcher = new AntPathRequestMatcher("/api/auth/login", HttpMethod.POST.name());
    private final Map<String, AttemptWindow> attempts = new ConcurrentHashMap<>();

    private static final class AttemptWindow {
        final AtomicInteger count = new AtomicInteger();
        volatile Instant blockedUntil;
        boolean blocked() {
            Instant until = blockedUntil;
            return until != null && Instant.now().isBefore(until);
        }
    }

    public JsonLoginFilter(AuthenticationManager authenticationManager,
                           SecurityContextRepository securityContextRepository,
                           SessionService sessionService) {
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.sessionService = sessionService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !loginMatcher.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String username = null;
        String password = null;
        try {
            Map<?, ?> body = objectMapper.readValue(request.getInputStream(), Map.class);
            username = text(body.get("username"));
            password = text(body.get("password"));
        } catch (Exception ignored) {
            // Fall through to the "missing fields" message below.
        }

        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            fail(response, 401, "Please enter both username and password.");
            return;
        }
        username = username.trim();

        String key = request.getRemoteAddr() + "|" + username.toLowerCase();
        AttemptWindow window = attempts.computeIfAbsent(key, k -> new AttemptWindow());
        if (window.blocked()) {
            fail(response, 429, "Too many failed attempts. Please try again in a few minutes.");
            return;
        }

        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(username, password));
            attempts.remove(key);

            org.springframework.security.core.context.SecurityContext context =
                    org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            securityContextRepository.saveContext(context, request, response);
            org.springframework.security.core.context.SecurityContextHolder.setContext(context);

            // Flattened by SessionService inside an open JPA session (safe for lazy student).
            Map<String, Object> payload = sessionService.describe(authentication.getName());
            response.setStatus(200);
            response.setContentType("application/json");
            objectMapper.writeValue(response.getOutputStream(), payload);
        } catch (AuthenticationException ex) {
            int failures = window.count.incrementAndGet();
            if (failures >= MAX_ATTEMPTS) {
                window.blockedUntil = Instant.now().plus(ATTEMPT_WINDOW);
                window.count.set(0);
                fail(response, 429, "Too many failed attempts. Please try again in a few minutes.");
                return;
            }
            fail(response, 401, messageFor(ex));
        }
    }

    private String messageFor(AuthenticationException ex) {
        if (ex instanceof DisabledException) {
            return "Your account is inactive. Please contact the administrator.";
        }
        if (ex instanceof UsernameNotFoundException) {
            return "Username not found. Please check your username or contact the administrator.";
        }
        if (ex instanceof BadCredentialsException) {
            return "Incorrect password. Please try again.";
        }
        return "Sign in failed. Please try again.";
    }

    private void fail(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        objectMapper.writeValue(response.getOutputStream(), Map.of("message", message));
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
