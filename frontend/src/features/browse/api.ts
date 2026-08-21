import { useQuery } from "@tanstack/react-query";

import { apiGet, type ApiError } from "@/lib/api";
import type { BrowseEvent, BrowseSession } from "./types";

const EVENTS_KEY = ["browse", "events"] as const;
const EVENT_KEY = ["browse", "event"] as const;
const SESSIONS_KEY = ["browse", "sessions"] as const;

/**
 * EventSearchController is genuinely public (no auth header sent, none
 * required — see that controller's javadoc) so this hook works identically
 * signed in or signed out, unlike everything in features/organizer.
 */
export function useBrowseEvents(query: string) {
  const trimmed = query.trim();
  return useQuery<BrowseEvent[], ApiError>({
    queryKey: [...EVENTS_KEY, trimmed],
    queryFn: () =>
      apiGet<BrowseEvent[]>(
        trimmed ? `/api/v1/events?q=${encodeURIComponent(trimmed)}` : "/api/v1/events",
      ),
  });
}

/** EventSearchController.getById — same public carve-out as the search above. */
export function useBrowseEventById(eventId: string) {
  return useQuery<BrowseEvent, ApiError>({
    queryKey: [...EVENT_KEY, eventId],
    queryFn: () => apiGet<BrowseEvent>(`/api/v1/events/${eventId}`),
    enabled: Boolean(eventId),
  });
}

/** PublicSessionController.list — public, no auth header sent. */
export function useEventSessions(eventId: string) {
  return useQuery<BrowseSession[], ApiError>({
    queryKey: [...SESSIONS_KEY, eventId],
    queryFn: () => apiGet<BrowseSession[]>(`/api/v1/events/${eventId}/sessions`),
    enabled: Boolean(eventId),
  });
}
