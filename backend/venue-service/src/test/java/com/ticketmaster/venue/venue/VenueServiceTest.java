package com.ticketmaster.venue.venue;

import com.ticketmaster.venue.shared.outbox.OutboxPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Focused on ADR-030's ownership-check logic — the security-sensitive
 * part of this slice — per the task brief. Basic CRUD happy-path is
 * covered at the controller level instead.
 */
@ExtendWith(MockitoExtension.class)
class VenueServiceTest {

    @Mock
    VenueRepository venues;

    @Mock
    OutboxPublisher outbox;

    VenueService venueService;

    UUID organizerId;
    UUID venueId;
    Venue venue;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-20T00:00:00Z"), ZoneOffset.UTC);
        venueService = new VenueService(venues, outbox, clock);

        organizerId = UUID.randomUUID();
        venueId = UUID.randomUUID();
        venue = new Venue(venueId, organizerId, "Arena", "123 Main St", "Metropolis", "US", Instant.now(clock));
    }

    @Test
    void ownerCanAccessTheirOwnVenue() {
        when(venues.findByIdAndOrganizerId(venueId, organizerId)).thenReturn(Optional.of(venue));

        Venue found = venueService.getVenue(venueId, organizerId, false);

        assertThat(found).isSameAs(venue);
    }

    @Test
    void nonOwnerGetsNotFoundNeverForbidden() {
        UUID attackerId = UUID.randomUUID();
        when(venues.findByIdAndOrganizerId(venueId, attackerId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> venueService.getVenue(venueId, attackerId, false))
                .isInstanceOf(VenueNotFoundException.class);
    }

    @Test
    void adminBypassesOwnershipCheck() {
        UUID adminCallerId = UUID.randomUUID();
        // Admin lookup goes through findById, never findByIdAndOrganizerId —
        // ADR-030's explicit bypass.
        when(venues.findById(venueId)).thenReturn(Optional.of(venue));

        Venue found = venueService.getVenue(venueId, adminCallerId, true);

        assertThat(found).isSameAs(venue);
    }

    @Test
    void adminStillGetsNotFoundForATrulyUnknownVenue() {
        UUID adminCallerId = UUID.randomUUID();
        when(venues.findById(venueId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> venueService.getVenue(venueId, adminCallerId, true))
                .isInstanceOf(VenueNotFoundException.class);
    }

    @Test
    void createVenuePublishesVenueCreatedInTheSameTransaction() {
        when(venues.saveAndFlush(any(Venue.class))).thenAnswer(invocation -> invocation.getArgument(0));

        venueService.createVenue(organizerId, "Arena", "123 Main St", "Metropolis", "US");

        org.mockito.Mockito.verify(outbox).publish(org.mockito.ArgumentMatchers.eq("venue.created"), any(), any());
    }
}
