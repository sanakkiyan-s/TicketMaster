/**
 * Mirrors search-service's EventSearchResult exactly (see
 * EventSearchResult.java) — eventId/organizerId/venueId/title/status/region
 * only. No description, dates, images, or price: EventService.toPayload
 * (event-service) never publishes them to the outbox, so search-service's
 * index genuinely does not have them yet — a documented gap, not an
 * oversight here.
 */
export interface BrowseEvent {
  eventId: string;
  organizerId: string;
  venueId: string;
  title: string;
  status: string;
  region: string;
}
