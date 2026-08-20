package com.ticketmaster.auth.user;

import jakarta.persistence.*;
import lombok.Getter;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A user account. Owns credentials and roles only - profile data lives in
 * user-service (ADR-001).
 *
 * Lombok @Getter only, deliberately not @Data or @Setter. On a JPA entity
 * holding a password hash those are actively harmful: @ToString prints the
 * hash into any log line that touches a User; @EqualsAndHashCode includes
 * every field, so hashCode changes as JPA populates them and equality can
 * trigger a lazy load; @Setter makes every field mutable, defeating the
 * constructor that establishes the invariants.
 */
@Entity
@Table(name = "users")
@Getter
public class User {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * Stored in a CITEXT column, so equality is case-insensitive in the
     * database itself. The application must not lower-case on the way in:
     * doing so would silently discard the casing the user typed while
     * adding nothing, since the column already treats the two as equal.
     */
    @Column(name = "email", nullable = false, unique = true, columnDefinition = "citext")
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "role", nullable = false)
    private Set<String> roles = new HashSet<>();

    /**
     * The persistent side of ADR-040's login lockout: LoginAttemptLimiter
     * decides WHEN to trip (its Redis fast/slow windows), this is the one
     * DB write that follows, surviving past either Redis window's own TTL.
     */
    @Column(name = "locked_until")
    private Instant lockedUntil;

    protected User() {
        // JPA
    }

    public User(String email, String passwordHash, Instant now) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.createdAt = now;
        this.updatedAt = now;
        this.roles.add(Role.USER);
    }

    /**
     * Hand-written, overriding Lombok: returns a copy so a caller cannot
     * grant itself a role by mutating the set it was handed.
     */
    public Set<String> getRoles() {
        return Set.copyOf(roles);
    }

    public boolean isLocked(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    /**
     * Called once LoginAttemptLimiter's Redis windows (ADR-040) report
     * either threshold crossed by the current failure - not on every
     * failed attempt, only the one that trips it. That is the whole
     * point of moving counting to Redis: this is the single DB write per
     * lock cycle, not one per attempt.
     */
    public void lock(Instant now, Duration lockDuration) {
        this.lockedUntil = now.plus(lockDuration);
        this.updatedAt = now;
    }

    /** Called on every successful login - a real password clears the slate. */
    public void unlock(Instant now) {
        if (this.lockedUntil != null) {
            this.lockedUntil = null;
            this.updatedAt = now;
        }
    }
}
