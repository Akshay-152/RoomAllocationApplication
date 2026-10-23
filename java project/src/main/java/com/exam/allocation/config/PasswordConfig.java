package com.exam.allocation.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Password hashing lives in its own configuration class on purpose: the account
 * service needs a {@link PasswordEncoder}, and defining it inside
 * {@link SecurityConfig} would make the service depend back on the security
 * filter chain and create a bean cycle.
 */
@Configuration
public class PasswordConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
