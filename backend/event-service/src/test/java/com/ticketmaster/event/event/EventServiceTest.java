package com.ticketmaster.event.event;

import com.ticketmaster.event.shared.outbox.OutboxPublisher;
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
class EventServiceTest {

    @Mock
    EventRepository events;

    @Mock
    OutboxPublisher outbox;

    EventService eventService;

    UUID organizerId;
    UUID eventId;
    Event event;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-20T00:00:00Z"), ZoneOffset.UTC);
        eventService = new EventService(events, outbox, clock);

        organizerId = UUID.randomUUID();
        eventId = UUID.randomUUID();
        event = new Event(eventId, UUID.randomUUID(), organizerId, "Title", "Desc", "music", "US", Instant.now(clock));
    }

    @Test
    void ownerCanAccessTheirOwnEvent() {
        when(events.findByIdAndOrganizerId(eventId, organizerId)).thenReturn(Optional.of(event));

        Event found = eventService.getEvent(eventId, organizerId, false);

        assertThat(found).isSameAs(event);
    }

    @Test
    void nonOwnerGetsNotFoundNeverForbidden() {
        UUID attackerId = UUID.randomUUID();
        when(events.findByIdAndOrganizerId(eventId, attackerId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> eventService.getEvent(eventId, attackerId, false))
                .isInstanceOf(EventNotFoundException.class);
    }

    @Test
    void adminBypassesOwnershipCheck() {
        UUID adminCallerId = UUID.randomUUID();
        // Admin lookup goes through findById, never findByIdAndOrganizerId —
        // ADR-030's explicit bypass.
        when(events.findById(eventId)).thenReturn(Optional.of(event));

        Event found = eventService.getEvent(eventId, adminCallerId, true);

        assertThat(found).isSameAs(event);
    }

    @Test
    void adminStillGetsNotFoundForATrulyUnknownEvent() {
        UUID adminCallerId = UUID.randomUUID();
        when(events.findById(eventId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> eventService.getEvent(eventId, adminCallerId, true))
                .isInstanceOf(EventNotFoundException.class);
    }

    @Test
    void createEventPublishesEventCreatedInTheSameTransaction() {
        when(events.saveAndFlush(any(Event.class))).thenAnswer(invocation -> invocation.getArgument(0));

        eventService.createEvent(organizerId, UUID.randomUUID(), "Title", "Desc", "music", "US");

        org.mockito.Mockito.verify(outbox).publish(org.mockito.ArgumentMatchers.eq("event.created"), any(), any());
    }

    @Test
    void publishingADraftEventSetsStatusAndEmitsEventUpdated() {
        when(events.findByIdAndOrganizerId(eventId, organizerId)).thenReturn(Optional.of(event));

        Event published = eventService.publishEvent(eventId, organizerId, false);

        assertThat(published.getStatus()).isEqualTo(EventStatus.PUBLISHED);
        org.mockito.Mockito.verify(outbox).publish(org.mockito.ArgumentMatchers.eq("event.updated"), any(), any());
    }

    @Test
    void publishingAnAlreadyPublishedEventIsRejected() {
        event.publish(Instant.now());
        when(events.findByIdAndOrganizerId(eventId, organizerId)).thenReturn(Optional.of(event));

        assertThatThrownBy(() -> eventService.publishEvent(eventId, organizerId, false))
                .isInstanceOf(InvalidEventStateException.class);
    }

    @Test
    void publishingACancelledEventIsRejected() {
        event.cancel(Instant.now());
        when(events.findByIdAndOrganizerId(eventId, organizerId)).thenReturn(Optional.of(event));

        assertThatThrownBy(() -> eventService.publishEvent(eventId, organizerId, false))
                .isInstanceOf(InvalidEventStateException.class);
    }
}
