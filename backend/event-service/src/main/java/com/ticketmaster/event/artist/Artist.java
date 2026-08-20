package com.ticketmaster.event.artist;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * Shared catalog reference data — any organizer can create or reference an
 * artist, no ADR-030 ownership check applies (see the task brief: artists
 * are treated like simple reference data, not organizer-owned).
 *
 * Lombok @Getter only — ADR-037 rule 6.
 */
@Entity
@Table(name = "artists")
@Getter
public class Artist {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "bio")
    private String bio;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Artist() {
        // JPA
    }

    public Artist(UUID id, String name, String bio, Instant now) {
        this.id = id;
        this.name = name;
        this.bio = bio;
        this.createdAt = now;
        this.updatedAt = now;
    }
}
