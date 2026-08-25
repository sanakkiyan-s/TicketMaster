package com.ticketmaster.search.event;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EventKafkaConsumerTest {

    @Mock
    private EventDocumentRepository events;

    private EventKafkaConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new EventKafkaConsumer(events, new ObjectMapper());
    }

    @Test
    void onEventUpserted_malformedJson_neverTouchesRepositoryAndNeverThrows() {
        // A poison message must not take down the consumer thread — this
        // is the requirement, not just "doesn't corrupt the index".
        assertThatCode(() -> consumer.onEventUpserted("{not valid json"))
                .doesNotThrowAnyException();

        verify(events, never()).save(any());
    }

    @Test
    void onEventCancelled_malformedJson_neverTouchesRepositoryAndNeverThrows() {
        assertThatCode(() -> consumer.onEventCancelled("<<garbage>>"))
                .doesNotThrowAnyException();

        verify(events, never()).save(any());
        verify(events, never()).findById(any());
    }

    @Test
    void onEventUpserted_missingEventId_skipsWithoutTouchingRepository() {
        assertThatCode(() -> consumer.onEventUpserted("{\"title\": \"no id here\"}"))
                .doesNotThrowAnyException();

        verify(events, never()).save(any());
    }

    @Test
    void onEventUpserted_validPayload_savesIndexedDocument() {
        consumer.onEventUpserted(
                "{\"eventId\": \"evt-1\", \"organizerId\": \"org-1\", \"venueId\": \"ven-1\", "
                        + "\"title\": \"Concert\", \"status\": \"PUBLISHED\", \"region\": \"us-east\"}");

        verify(events).save(any(EventDocument.class));
    }

    @Test
    void onEventCancelled_existingDocument_mergesStatusOnly() {
        EventDocument existing = new EventDocument("evt-1", "org-1", "ven-1", "Concert", "PUBLISHED", "us-east");
        when(events.findById("evt-1")).thenReturn(Optional.of(existing));

        consumer.onEventCancelled("{\"eventId\": \"evt-1\"}");

        verify(events).save(any(EventDocument.class));
    }
}
