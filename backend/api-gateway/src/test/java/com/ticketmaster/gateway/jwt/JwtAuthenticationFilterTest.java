package com.ticketmaster.gateway.jwt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketmaster.gateway.jwt.revocation.RevocationEntry;
import com.ticketmaster.gateway.jwt.revocation.RevocationStore;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.security.Key;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the filter against real RSA signatures with a stubbed key cache.
 *
 * The cache is stubbed rather than served over HTTP because what needs proving
 * is the DECISION the filter makes when a key is present or absent — above all
 * that an absent key produces a rejection and NOT a fetch.
 */
class JwtAuthenticationFilterTest {

    static final String ISSUER = "http://auth";
    static final String AUDIENCE = "ticketmaster-api";

    static KeyPair keyPair;
    static KeyPair strangerKeyPair;

    @BeforeAll
    static void generateKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
        strangerKeyPair = generator.generateKeyPair();
    }

    static JwtValidationProperties properties() {
        return new JwtValidationProperties(
                "http://auth/.well-known/jwks.json", ISSUER, AUDIENCE,
                Duration.ofMinutes(5), Duration.ofSeconds(30), Duration.ofSeconds(60),
                List.of("/api/v1/auth/login", "/actuator/health"));
    }

    /** A cache holding exactly the keys a test wants it to hold. */
    static class StubCache extends JwksCache {

        private final Map<String, Key> keys;
        private final boolean ready;
        int fetchCount = 0;

        StubCache(Map<String, Key> keys, boolean ready) {
            super(WebClient.builder(), new ObjectMapper(), properties());
            this.keys = keys;
            this.ready = ready;
        }

        @Override
        public Key find(String kid) {
            return keys.get(kid);
        }

        @Override
        public boolean isReady() {
            return ready;
        }

        @Override
        public void refresh() {
            fetchCount++;
        }
    }

    private String token(String kid, KeyPair signer, Instant issuedAt, Duration lifetime) {
        return token(kid, signer, issuedAt, lifetime, "user:abc", null);
    }

    private String token(String kid, KeyPair signer, Instant issuedAt, Duration lifetime,
            String subject, String sessionId) {
        var builder = Jwts.builder()
                .header().keyId(kid).and()
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .subject(subject)
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(issuedAt.plus(lifetime)));
        if (sessionId != null) {
            builder.claim("sid", sessionId);
        }
        return builder.signWith(signer.getPrivate(), Jwts.SIG.RS256).compact();
    }

    private HttpStatus statusFor(JwksCache cache, String authorization, String path) {
        return statusFor(cache, authorization, path, new RevocationStore());
    }

    private HttpStatus statusFor(JwksCache cache, String authorization, String path, RevocationStore revocations) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.get(path);
        if (authorization != null) {
            builder.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        MockServerWebExchange exchange = MockServerWebExchange.from(builder.build());

        new JwtAuthenticationFilter(cache, properties(), revocations)
                .filter(exchange, e -> Mono.empty())
                .block();

        return exchange.getResponse().getStatusCode() == null
                ? HttpStatus.OK
                : HttpStatus.resolve(exchange.getResponse().getStatusCode().value());
    }

    @Test
    void acceptsATokenSignedByAKnownKey() {
        StubCache cache = new StubCache(Map.of("k1", keyPair.getPublic()), true);

        assertEquals(HttpStatus.OK, statusFor(cache,
                "Bearer " + token("k1", keyPair, Instant.now(), Duration.ofMinutes(10)),
                "/api/v1/events"));
    }

    @Test
    void unknownKidIsRejectedWithoutFetchingJwks() {
        // The core anti-amplification property. If this regresses, an attacker
        // sending garbage kid values converts unauthenticated traffic into
        // outbound load on auth-service from every gateway instance at once.
        StubCache cache = new StubCache(Map.of("k1", keyPair.getPublic()), true);

        assertEquals(HttpStatus.UNAUTHORIZED, statusFor(cache,
                "Bearer " + token("k-unknown", keyPair, Instant.now(), Duration.ofMinutes(10)),
                "/api/v1/events"));

        assertEquals(0, cache.fetchCount, "the filter fetched JWKS on an unknown kid");
    }

    @Test
    void aFloodOfUnknownKidsNeverFetches() {
        StubCache cache = new StubCache(Map.of("k1", keyPair.getPublic()), true);

        for (int i = 0; i < 200; i++) {
            statusFor(cache,
                    "Bearer " + token("forged-" + i, keyPair, Instant.now(), Duration.ofMinutes(10)),
                    "/api/v1/events");
        }

        assertEquals(0, cache.fetchCount, "forged kids produced JWKS fetches");
    }

    @Test
    void rejectsATokenSignedByAKeyWeDoNotTrust() {
        // Same kid as a key we hold, different private key. The header is
        // attacker-controlled, so trusting kid alone would be trusting the
        // attacker's own claim about who signed the token.
        StubCache cache = new StubCache(Map.of("k1", keyPair.getPublic()), true);

        assertEquals(HttpStatus.UNAUTHORIZED, statusFor(cache,
                "Bearer " + token("k1", strangerKeyPair, Instant.now(), Duration.ofMinutes(10)),
                "/api/v1/events"));
    }

    @Test
    void rejectsAnExpiredToken() {
        StubCache cache = new StubCache(Map.of("k1", keyPair.getPublic()), true);

        String expired = token("k1", keyPair,
                Instant.now().minus(Duration.ofHours(1)), Duration.ofMinutes(10));

        assertEquals(HttpStatus.UNAUTHORIZED, statusFor(cache, "Bearer " + expired, "/api/v1/events"));
    }

    @Test
    void rejectsATokenMintedForAnotherAudience() {
        StubCache cache = new StubCache(Map.of("k1", keyPair.getPublic()), true);

        String otherAudience = Jwts.builder()
                .header().keyId("k1").and()
                .issuer(ISSUER)
                .audience().add("some-other-api").and()
                .subject("user:abc")
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plus(Duration.ofMinutes(10))))
                .signWith(keyPair.getPrivate(), Jwts.SIG.RS256)
                .compact();

        assertEquals(HttpStatus.UNAUTHORIZED,
                statusFor(cache, "Bearer " + otherAudience, "/api/v1/events"));
    }

    @Test
    void missingOrMalformedAuthorizationIsRejected() {
        StubCache cache = new StubCache(Map.of("k1", keyPair.getPublic()), true);

        assertEquals(HttpStatus.UNAUTHORIZED, statusFor(cache, null, "/api/v1/events"));
        assertEquals(HttpStatus.UNAUTHORIZED, statusFor(cache, "Basic abc123", "/api/v1/events"));
        assertEquals(HttpStatus.UNAUTHORIZED, statusFor(cache, "Bearer not-a-jwt", "/api/v1/events"));
    }

    @Test
    void loginIsReachableWithoutTheTokenItExistsToIssue() {
        StubCache cache = new StubCache(Map.of("k1", keyPair.getPublic()), true);

        assertEquals(HttpStatus.OK, statusFor(cache, null, "/api/v1/auth/login"));
    }

    @Test
    void answers503RatherThan401WhileJwksHasNeverLoaded() {
        // Telling a client with a perfectly good token that it is unauthorized
        // makes it discard the token and re-login, turning one gateway's cold
        // start into a login stampede against auth-service.
        StubCache neverLoaded = new StubCache(Map.of(), false);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, statusFor(neverLoaded,
                "Bearer " + token("k1", keyPair, Instant.now(), Duration.ofMinutes(10)),
                "/api/v1/events"));
    }

    @Test
    void forwardsTheAuthorizationHeaderRatherThanStrippingIt() {
        // Stripping the JWT and forwarding a plain X-User-Id header is only
        // safe when services are unreachable except through this gateway.
        // Without ADR-009's mesh mTLS, any workload could set that header and
        // become any user, so the verifiable credential travels onward.
        StubCache cache = new StubCache(Map.of("k1", keyPair.getPublic()), true);
        String bearer = "Bearer " + token("k1", keyPair, Instant.now(), Duration.ofMinutes(10));

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/events")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .build());

        AtomicReference<HttpHeaders> forwarded = new AtomicReference<>();

        new JwtAuthenticationFilter(cache, properties(), new RevocationStore())
                .filter(exchange, e -> {
                    forwarded.set(e.getRequest().getHeaders());
                    return Mono.empty();
                })
                .block();

        assertEquals(bearer, forwarded.get().getFirst(HttpHeaders.AUTHORIZATION));
        assertTrue(forwarded.get().containsKey("X-Request-Subject"));
    }

    @Test
    void aUserRevocationRejectsATokenIssuedBeforeRevokeBefore() {
        StubCache cache = new StubCache(Map.of("k1", keyPair.getPublic()), true);
        RevocationStore revocations = new RevocationStore();
        Instant revokeBefore = Instant.now();
        revocations.apply("user:banned", new RevocationEntry(revokeBefore, "fraud"));

        String tokenIssuedBeforeBan = token("k1", keyPair,
                revokeBefore.minus(Duration.ofMinutes(1)), Duration.ofMinutes(10), "user:banned", null);

        assertEquals(HttpStatus.UNAUTHORIZED,
                statusFor(cache, "Bearer " + tokenIssuedBeforeBan, "/api/v1/events", revocations));
    }

    @Test
    void aUserRevocationAllowsATokenIssuedAfterRevokeBefore() {
        // A fresh login/refresh minted after the ban must keep working -
        // otherwise a banned-then-unbanned user could never log back in.
        StubCache cache = new StubCache(Map.of("k1", keyPair.getPublic()), true);
        RevocationStore revocations = new RevocationStore();
        Instant revokeBefore = Instant.now();
        revocations.apply("user:banned", new RevocationEntry(revokeBefore, "fraud"));

        String tokenIssuedAfterBan = token("k1", keyPair,
                revokeBefore.plus(Duration.ofMinutes(1)), Duration.ofMinutes(10), "user:banned", null);

        assertEquals(HttpStatus.OK,
                statusFor(cache, "Bearer " + tokenIssuedAfterBan, "/api/v1/events", revocations));
    }

    @Test
    void aSessionRevocationRejectsOnlyThatSession() {
        StubCache cache = new StubCache(Map.of("k1", keyPair.getPublic()), true);
        RevocationStore revocations = new RevocationStore();
        Instant revokeBefore = Instant.now();
        revocations.apply("session:device-1", new RevocationEntry(revokeBefore, "logged out remotely"));

        String loggedOutSession = token("k1", keyPair,
                revokeBefore.minus(Duration.ofMinutes(1)), Duration.ofMinutes(10), "user:abc", "device-1");
        String otherSessionSameUser = token("k1", keyPair,
                revokeBefore.minus(Duration.ofMinutes(1)), Duration.ofMinutes(10), "user:abc", "device-2");

        assertEquals(HttpStatus.UNAUTHORIZED,
                statusFor(cache, "Bearer " + loggedOutSession, "/api/v1/events", revocations));
        assertEquals(HttpStatus.OK,
                statusFor(cache, "Bearer " + otherSessionSameUser, "/api/v1/events", revocations));
    }
}
