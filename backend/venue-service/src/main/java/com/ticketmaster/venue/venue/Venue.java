package com.ticketmaster.venue.venue;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * An organizer-managed venue and the anchor of its seat map (sections,
 * seats). organizer_id is ADR-030's ownership anchor: every mutating/
 * get-single endpoint checks this against the caller's identity. region is
 * fixed at creation, same ADR-016 data-residency reasoning as
 * event-service's Event.region — not updatable.
 *
 * Lombok @Getter only — see auth-service's User for why @Data/@Setter/
 * @ToString/@EqualsAndHashCode are not permitted on JPA entities
 * (ADR-037 rule 6). Mutation happens through named intent-revealing
 * methods (update), not generated setters.
 */
@Entity
@Table(name = "venues")
@Getter
public class Venue {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "organizer_id", nullable = false, updatable = false)
    private UUID organizerId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "address")
    private String address;

    @Column(name = "city")
    private String city;

    @Column(name = "region", nullable = false, updatable = false)
    private String region;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Venue() {
        // JPA
    }

    public Venue(UUID id, UUID organizerId, String name, String address, String city, String region, Instant now) {
        this.id = id;
        this.organizerId = organizerId;
        this.name = name;
        this.address = address;
        this.city = city;
        this.region = region;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void update(String name, String address, String city, Instant now) {
        this.name = name;
        this.address = address;
        this.city = city;
        this.updatedAt = now;
    }
}
