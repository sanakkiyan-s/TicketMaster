package com.ticketmaster.search.event;

import java.util.List;
import org.springframework.data.elasticsearch.annotations.Query;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

public interface EventDocumentRepository extends ElasticsearchRepository<EventDocument, String> {

    // A derived findByTitle... method on a Text field still compiles, but
    // translates to a term/wildcard match, not the relevance-scored full
    // text search this is actually for — an explicit match query is what
    // "search title by relevance" means.
    @Query("{\"match\": {\"title\": \"?0\"}}")
    List<EventDocument> searchByTitle(String query);
}
