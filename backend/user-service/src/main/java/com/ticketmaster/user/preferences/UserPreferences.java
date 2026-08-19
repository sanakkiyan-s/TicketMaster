package com.ticketmaster.user.preferences;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * Same userId-as-PK, no-FK pattern as profile/UserProfile — see that
 * class's javadoc (ADR-001).
 *
 * Lombok @Getter only — ADR-037 rule 6.
 */
@Entity
@Table(name = "user_preferences")
@Getter
public class UserPreferences {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "email_opt_in", nullable = false)
    private boolean emailOptIn;

    @Column(name = "sms_opt_in", nullable = false)
    private boolean smsOptIn;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserPreferences() {
        // JPA
    }

    /** Defaults: emailOptIn true, smsOptIn false — the wiki spec's stated defaults. */
    public UserPreferences(UUID userId, Instant now) {
        this.userId = userId;
        this.emailOptIn = true;
        this.smsOptIn = false;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void update(boolean emailOptIn, boolean smsOptIn, Instant now) {
        this.emailOptIn = emailOptIn;
        this.smsOptIn = smsOptIn;
        this.updatedAt = now;
    }
}
