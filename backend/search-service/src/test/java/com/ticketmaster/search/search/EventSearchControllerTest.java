package com.ticketmaster.search.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ticketmaster.search.event.EventDocument;
import com.ticketmaster.search.event.EventDocumentRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EventSearchControllerTest {

    @Mock
    private EventDocumentRepository events;

    private EventSearchController controller;

    @BeforeEach
    void setUp() {
        controller = new EventSearchController(events);
    }

    @Test
    void search_blankQuery_returnsEveryIndexedDocument() {
        EventDocument doc = new EventDocument("evt-1", "org-1", "ven-1", "Concert", "PUBLISHED", "us-east");
        when(events.findAll()).thenReturn(List.of(doc));

        List<EventSearchResult> result = controller.search("");

        assertThat(result).containsExactly(EventSearchResult.from(doc));
    }

    @Test
    void search_nonBlankQuery_delegatesToRelevanceSearch() {
        EventDocument doc = new EventDocument("evt-1", "org-1", "ven-1", "Jazz Night", "PUBLISHED", "us-east");
        when(events.searchByTitle("jazz")).thenReturn(List.of(doc));

        List<EventSearchResult> result = controller.search("jazz");

        assertThat(result).containsExactly(EventSearchResult.from(doc));
        verify(events).searchByTitle("jazz");
    }

    @Test
    void getById_found_returnsMappedResult() {
        EventDocument doc = new EventDocument("evt-1", "org-1", "ven-1", "Concert", "PUBLISHED", "us-east");
        when(events.findById("evt-1")).thenReturn(Optional.of(doc));

        assertThat(controller.getById("evt-1")).isEqualTo(EventSearchResult.from(doc));
    }

    @Test
    void getById_missing_throwsEventNotFoundException() {
        when(events.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.getById("missing"))
                .isInstanceOf(EventNotFoundException.class);
    }
}
