package com.ticketmaster.event.artist;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * Shared catalog CRUD — no organizer-ownership check (see Artist's
 * javadoc). The coarse ORGANIZER-role gate at api-gateway is the only
 * authorization layer for these endpoints.
 *
 * No springdoc annotations — see EventController's javadoc for why.
 */
@RestController
@RequestMapping("/api/v1/organizer/artists")
public class ArtistController {

    private final ArtistService artistService;

    public ArtistController(ArtistService artistService) {
        this.artistService = artistService;
    }

    /** Create an artist. */
    @PostMapping
    public ResponseEntity<ArtistResponse> create(@Valid @RequestBody CreateArtistRequest body) {
        Artist created = artistService.create(body.name(), body.bio());
        return ResponseEntity
                .created(URI.create("/api/v1/organizer/artists/" + created.getId()))
                .body(ArtistResponse.from(created));
    }

    /** Get a single artist. */
    @GetMapping("/{id}")
    public ArtistResponse get(@PathVariable UUID id) {
        return ArtistResponse.from(artistService.get(id));
    }

    /** List/search artists by name — simple case-insensitive substring match, no search-service integration. */
    @GetMapping
    public List<ArtistResponse> search(@RequestParam(required = false) String name) {
        return artistService.search(name).stream()
                .map(ArtistResponse::from)
                .toList();
    }
}
