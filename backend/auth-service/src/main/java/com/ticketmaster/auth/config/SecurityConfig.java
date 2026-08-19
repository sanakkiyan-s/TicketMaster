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

                        // Login must be reachable without a credential — it is
                        // where credentials are exchanged. Rate limiting at the
                        // gateway (ADR-014) is what keeps it from being a free
                        // password-guessing endpoint; BCrypt strength 12 makes
                        // each guess expensive for the attacker AND for us,
                        // which is exactly why the throttle is not optional.
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()

                        // Permitted because the refresh COOKIE is the
                        // credential, not an Authorization header - the whole
                        // point is that it works when the access token has
                        // already expired.
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/refresh").permitAll()
                        // ADR-032: probes must answer without credentials,
                        // or a healthy pod looks unhealthy to Kubernetes.
                        .requestMatchers("/actuator/health/**").permitAll()

                        // JWKS is public BY DESIGN — it carries only public key
                        // material, and every gateway must fetch it without a
                        // credential (a token cannot be the credential for
                        // fetching the keys that verify tokens). Unlike the
                        // OpenAPI endpoints below, this one is safe at the edge.
                        .requestMatchers(HttpMethod.GET, "/.well-known/jwks.json").permitAll()

                        // The generated spec and its viewer (ADR-034).
                        // Unauthenticated ON PURPOSE, but only safe
                        // because these are not public: api-gateway must
                        // not route /v3/api-docs or /swagger-ui to the
                        // internet, and SWAGGER_UI_ENABLED is set false in
                        // production. An openly reachable spec hands an
                        // attacker the full endpoint and field inventory.
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                        .permitAll()
                        .anyRequest().authenticated()
                )

                // Default is a login form; this service serves no HTML.
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable());

        return http.build();
    }
}
