/**
 * Field-for-field mirror of event-service's real DTOs (EventResponse,
 * CreateEventRequest, UpdateEventRequest, SessionResponse,
 * CreateSessionRequest, ArtistResponse, CreateArtistRequest — read from
 * backend/event-service/src/main/java/com/ticketmaster/event/**), not a
 * guess. Timestamps stay as the raw ISO strings the JSON wire format uses;
 * nothing here parses them into Date, so a round-trip PUT never has to
 * re-serialize a value it half-understood.
 */

export type EventStatus = "DRAFT" | "PUBLISHED" | "CANCELLED";
export type SessionStatus = "SCHEDULED" | "ON_SALE" | "CANCELLED" | "COMPLETED";

export interface OrganizerEvent {
  id: string;
  venueId: string;
  organizerId: string;
  title: string;
  description: string | null;
  category: string | null;
  status: EventStatus;
  region: string;
  createdAt: string;
  updatedAt: string;
}

/** region is required at creation (EventStatus is data-residency anchored here, per ADR-016) but never editable afterward — see UpdateEventRequest's javadoc. */
export interface CreateEventInput {
  venueId: string;
  title: string;
  description?: string;
  category?: string;
  region: string;
}

/** No region field — UpdateEventRequest deliberately excludes it (backend-enforced, not just a UI omission). */
export interface UpdateEventInput {
  venueId: string;
  title: string;
  description?: string;
  category?: string;
}

export interface OrganizerSession {
  id: string;
  eventId: string;
  startsAt: string;
  endsAt: string | null;
  status: SessionStatus;
  onSaleAt: string | null;
  highDemand: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface CreateSessionInput {
  startsAt: string;
  endsAt?: string;
  onSaleAt?: string;
}

export interface OrganizerArtist {
  id: string;
  name: string;
  bio: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface CreateArtistInput {
  name: string;
  bio?: string;
}
