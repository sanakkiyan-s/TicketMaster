package com.ticketmaster.user.profile;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * A user's profile fields. userId is the same value auth-service assigned
 * as its users.id — copied in as a plain UUID column, never a JPA
 * relationship (ADR-001: each service owns its own database, no
 * cross-service foreign keys).
 *
 * Lombok @Getter only — see auth-service's User for why @Data/@Setter/
 * @ToString/@EqualsAndHashCode are not permitted on JPA entities
 * (ADR-037 rule 6).
 */
@Entity
@Table(name = "user_profiles")
@Getter
public class UserProfile {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "phone_number")
    private String phoneNumber;

    @Column(name = "avatar_url")
    private String avatarUrl;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserProfile() {
        // JPA
    }

    /** Auto-created on first GET/PUT for a userId with no row yet — see ProfileService. */
    public UserProfile(UUID userId, Instant now) {
        this.userId = userId;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void update(String displayName, String phoneNumber, String avatarUrl, Instant now) {
        this.displayName = displayName;
        this.phoneNumber = phoneNumber;
        this.avatarUrl = avatarUrl;
        this.updatedAt = now;
    }
}
