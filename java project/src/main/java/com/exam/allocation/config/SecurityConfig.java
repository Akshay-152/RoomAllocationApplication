package com.exam.allocation.config;

import com.exam.allocation.filter.JsonLoginFilter;
import com.exam.allocation.service.SessionService;
import com.exam.allocation.service.UserAccountService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

/**
 * Security rules for the application.
 *
 * <ul>
 *   <li>Public: the SPA shell plus the login/csrf/logout endpoints.</li>
 *   <li>{@code /api/student/**} - signed-in students only (records scoped to the caller).</li>
 *   <li>Everything else under {@code /api/**} - administrators only.</li>
 *   <li>CSRF tokens are issued as a readable {@code XSRF-TOKEN} cookie and must be echoed
 *       back in the {@code X-XSRF-TOKEN} header; the SPA's {@code api()} helper does that.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final UserAccountService userAccountService;

    public SecurityConfig(UserAccountService userAccountService) {
        this.userAccountService = userAccountService;
    }

    /** Shared bean so the login filter saves into the same context store the chain uses. */
    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public DaoAuthenticationProvider authenticationProvider(PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userAccountService);
        provider.setPasswordEncoder(passwordEncoder);
        // Let UsernameNotFoundException surface so the login filter can answer
        // "Username not found" instead of a generic "bad credentials".
        provider.setHideUserNotFoundExceptions(false);
        return provider;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, DaoAuthenticationProvider authenticationProvider,
                                           SessionService sessionService) throws Exception {
        // Build the manager locally from the provider instead of constructor-injecting
        // the AuthenticationManager bean, which would close the dependency cycle.
        AuthenticationManager authenticationManager = new ProviderManager(authenticationProvider);

        http.csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                // The login request is itself protected by the XSRF cookie dance performed
                // by the SPA; skipping it here keeps the very first request simple.
                .ignoringRequestMatchers(new AntPathRequestMatcher("/api/auth/login", "POST")));

        http.securityContext(sc -> sc.securityContextRepository(securityContextRepository()));

        http.sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED));

        http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/", "/index.html", "/app.css", "/app.js", "/favicon.ico", "/error",
                        // Push support: the service worker must be fetchable while
                        // signed out; the icons are static notification images.
                        "/sw.js", "/icons/*", "/icons/**").permitAll()
                .requestMatchers("/api/auth/login", "/api/auth/csrf", "/api/auth/logout").permitAll()
                // administrator area
                .requestMatchers("/api/students", "/api/students/**",
                                  "/api/rooms", "/api/rooms/**",
                                  "/api/exams", "/api/exams/**",
                                  "/api/batches", "/api/batches/**",
                                  "/api/import/**", "/api/chart/**",
                                  "/api/allocate", "/api/seats", "/api/seats/**",
                                  "/api/allocations", "/api/allocations/**",
                                  "/api/departments", "/api/departments/**",
                                  "/api/subjects", "/api/subjects/**",
                                  "/api/admin", "/api/admin/**").hasRole("ADMIN")
                // student self-service area (records filtered by the signed-in student)
                .requestMatchers("/api/student", "/api/student/**").hasRole("STUDENT")
                .anyRequest().authenticated());

        http.formLogin(fl -> fl.disable());
        http.httpBasic(b -> b.disable());
        http.logout(lo -> lo
                .logoutUrl("/api/auth/logout")
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID", "XSRF-TOKEN")
                .logoutSuccessHandler((request, response, authentication) -> {
                    response.setStatus(200);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"message\":\"Logged out.\"}");
                }));

        http.exceptionHandling(eh -> eh
                .authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"message\":\"Please sign in to continue.\"}");
                })
                .accessDeniedHandler((request, response, accessDeniedException) -> {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"message\":\"You do not have permission to perform this action.\"}");
                }));

        http.addFilterBefore(
                new JsonLoginFilter(authenticationManager, securityContextRepository(), sessionService),
                UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
