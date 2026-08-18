package com.ticketmaster.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

import java.time.Clock;

@Configuration
public class SecurityConfig {

    /**
     * BCrypt at strength 12 (~250ms per hash on current hardware).
     *
     * The cost is the point: it bounds how fast a leaked password_hash
     * column can be brute-forced offline. It also bounds how fast this
     * endpoint can be called, which is why ADR-014's rate limiting at the
     * gateway matters here — an unthrottled register endpoint is a CPU
     * exhaustion vector precisely BECAUSE hashing is deliberately slow.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    /**
     * Injected rather than called statically so tests can freeze time.
     * Timestamps are application-supplied, never SQL now() — ADR-002.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // No CSRF token: this service is stateless and holds no
                // session cookie, so there is no ambient credential for a
                // cross-site request to ride on. CSRF protection defends
                // cookie-authenticated state-changing requests; there are
                // none here.
                .csrf(csrf -> csrf.disable())

                // ADR-012 issues tokens; nothing here is session-backed.
                // STATELESS also means ADR-032 can treat every replica as
                // interchangeable with no sticky routing.
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/register").permitAll()
                        // ADR-032: probes must answer without credentials,
                        // or a healthy pod looks unhealthy to Kubernetes.
                        .requestMatchers("/actuator/health/**").permitAll()
                        .anyRequest().authenticated()
                )

                // Default is a login form; this service serves no HTML.
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable());

        return http.build();
    }
}
