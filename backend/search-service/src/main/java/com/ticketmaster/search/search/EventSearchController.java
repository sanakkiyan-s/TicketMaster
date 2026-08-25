package com.ticketmaster.search.search;

import com.ticketmaster.search.event.EventDocument;
import com.ticketmaster.search.event.EventDocumentRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one genuinely public, unauthenticated read endpoint in the backend
 * so far (per final-architecture-reference.md's Search Architecture
 * section) — no CurrentUserResolver, no ownership check. api-gateway
 * routes here without a JWT requirement, same as auth's own
 * register/login/refresh public-paths carve-out.
 */
@RestController
@RequestMapping("/api/v1/events")
public class EventSearchController {

    private final EventDocumentRepository events;

    public EventSearchController(EventDocumentRepository events) {
        this.events = events;
    }

    @GetMapping
    public List<EventSearchResult> search(@RequestParam(name = "q", required = false, defaultValue = "") String query) {
        List<EventDocument> documents = query.isBlank()
                ? toList(events.findAll())
                : events.searchByTitle(query);
        return documents.stream().map(EventSearchResult::from).toList();
    }

    @GetMapping("/{id}")
    public EventSearchResult getById(@PathVariable String id) {
        return events.findById(id)
                .map(EventSearchResult::from)
                .orElseThrow(() -> new EventNotFoundException(id));
    }

    // ElasticsearchRepository.findAll() returns Iterable, not List — this
    // service has no pagination/facet story yet (search-service.md's own
    // Open Questions), so a full materialization for the empty-query case
    // is the simplest thing that is still correct today.
    private static List<EventDocument> toList(Iterable<EventDocument> source) {
        List<EventDocument> result = new ArrayList<>();
        source.forEach(result::add);
        return result;
    }
}
