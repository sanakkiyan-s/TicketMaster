---
title: search-service
type: project
sources: []
related: [[system-overview]], [[event-service]], [[venue-service]]
created: 2026-08-05
last-updated: 2026-08-20
---

## Purpose

Discovery/search: browse and filter events by artist, venue, city,
category, date. Read-optimized projection, not a source of truth.

## Current Implementation

Built as the third Phase 2 catalog service (ADR-036), Spring Boot +
Spring Data Elasticsearch, package root `com.ticketmaster.search`:

- `event/EventDocument` — `@Document(indexName = "events")`, fields
  limited to what event-service's outbox actually publishes today
  (`EventService.toPayload`,
  `backend/event-service/src/main/java/com/ticketmaster/event/event/EventService.java`):
  `eventId`, `organizerId`, `venueId`, `title` (Text-analyzed for
  relevance search), `status`, `region`. `title` is the only field this
  service currently searches by relevance
  (`EventDocumentRepository.searchByTitle`, a `@Query` match query).
- `event/EventKafkaConsumer` — `@KafkaListener` on `event.created` /
  `event.updated` (upsert) and `event.cancelled` (merges just the
  `status` field into an existing document via `EventDocument.withStatus`,
  falling back to indexing whatever the cancellation payload carries if
  no prior document exists). Malformed messages are logged at WARN and
  dropped — never thrown, so a poison message cannot take the consumer
  thread down (`EventKafkaConsumerTest` covers this directly).
- `search/EventSearchController` — `GET /api/v1/events?q=` (relevance
  search on `title`, or all documents if `q` is blank/omitted) and
  `GET /api/v1/events/{id}`. Deliberately **no auth** — the one genuinely
  public, unauthenticated read endpoint in the backend so far; no
  `CurrentUserResolver`, no ownership check.
- `infra/docker-compose.yml` — added `elasticsearch` (single-node dev
  mode, `xpack.security.enabled=false`, host port 9200) and
  `search-service` (host port 8086), both gated behind the `backend`
  profile, `search-service` depending on `elasticsearch` being
  `service_healthy` and `kafka` being started. `scripts/dev.sh`'s
  `BACKEND_HEALTHCHECKED` array and `print_backend_endpoints` were
  updated to include it.
- venue-service consumption (`venue.updated`) is **not implemented** —
  only the `event.*` topics are consumed. See Gap below.

**Elasticsearch vs. OpenSearch — resolved.** Picked **Elasticsearch**
(`docker.elastic.co/elasticsearch/elasticsearch:8.15.3`) over OpenSearch.
Both are functionally similar for this use case (the prior open
question's own framing), so the tiebreaker was ecosystem/documentation
footprint for a solo, learning-focused portfolio project — Elasticsearch
has the larger one, and this repo already reaches for common,
well-documented tooling elsewhere in its infra stack. No functional
requirement favored either engine.

**Testing:** `EventKafkaConsumerTest` (Mockito) covers malformed-JSON and
missing-`eventId` messages never touching the repository and never
throwing, plus a valid-upsert and a cancel-merge case.
`EventSearchControllerTest` (Mockito) covers the search/get-by-id/404
paths against a mocked repository. A Testcontainers-backed Elasticsearch
integration test (real index write → real read through the controller)
was written but could not be run or verified in the build environment
used for this work — Docker Desktop's daemon was not reachable there
(`docker info` failed to connect), which is an environment constraint of
that session, not a decision about the test's value. The test file was
removed rather than committed unverified; re-adding it (write to
`EventDocumentRepository`, then `GET /api/v1/events/{id}` via
`TestRestTemplate` against a `Testcontainers` `ElasticsearchContainer`) is
straightforward wherever Docker is available — `org.testcontainers:elasticsearch:1.21.4`
is already wired as a `testImplementation` in
`backend/search-service/build.gradle.kts` for exactly that purpose.
`./gradlew :backend:search-service:build` passes (10 unit tests green).
Live `docker compose --profile backend up elasticsearch search-service`
was **not** verified in this session for the same Docker-unavailability
reason — the compose config was written and reviewed against this repo's
existing service patterns but not exercised live.

## Target Design

- Spring Boot, Elasticsearch as the index store (see decision above).
- Consumes `event.created`/`event.updated`/`event.cancelled` and
  `venue.updated` from Kafka, updates its denormalized index
  asynchronously.
- Eventually consistent by design — a brand-new event may take seconds to
  appear in search. Documented tradeoff, not a bug.
- No writes flow through search-service; it's read-only from the client's
  perspective.

## Gap

- `venue.updated` is not consumed — no `VenueDocument`/venue-derived
  fields exist yet. When venue-service's outbox payload shape is settled,
  this needs its own consumer and a decision on whether venue data
  merges into `EventDocument` (denormalized) or lives as a second index.
- `description`/`category` exist on event-service's `Event` row
  (`backend/event-service/.../event/Event.java`) but are **not** in the
  outbox payload (`EventService.toPayload` only publishes eventId,
  organizerId, venueId, title, status, region) — so they cannot be
  indexed or searched here without event-service publishing them first.
  This is a current mismatch between "what the target design implies a
  search index should hold" and "what's actually on the wire" — flagging
  per this vault's citation convention rather than guessing at the
  fields.
- No pagination/facet support on `GET /api/v1/events` — returns the full
  match set. Filter/facet schema (date, location, genre, ticket type,
  accessibility) is still undesigned (see Open Questions).
- No api-gateway route added for search-service — out of scope for this
  task (api-gateway's config was reserved for other in-flight work); the
  public API is reachable directly at `search-service:8086` today, not
  yet through the gateway.
- The Testcontainers-backed indexing integration test described above was
  written and compiles but is not currently in the test suite — see
  Current Implementation's Testing note.

## Open Questions

- Filter/facet schema (date, location, genre, ticket type, accessibility) — not yet designed.
- Whether venue data should denormalize into `EventDocument` or live in
  its own index, once `venue.updated` consumption is built.
