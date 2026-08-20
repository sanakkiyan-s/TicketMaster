import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { apiGet, apiPost, apiPut, type ApiError } from "@/lib/api";
import type {
  CreateArtistInput,
  CreateEventInput,
  CreateSessionInput,
  OrganizerArtist,
  OrganizerEvent,
  OrganizerSession,
  UpdateEventInput,
} from "./types";

const EVENTS_KEY = ["organizer", "events"] as const;
const eventKey = (id: string) => [...EVENTS_KEY, id] as const;
const ARTISTS_KEY = ["organizer", "artists"] as const;

export function useOrganizerEvents() {
  return useQuery<OrganizerEvent[], ApiError>({
    queryKey: EVENTS_KEY,
    queryFn: () => apiGet<OrganizerEvent[]>("/api/v1/organizer/events"),
  });
}

export function useOrganizerEvent(id: string) {
  return useQuery<OrganizerEvent, ApiError>({
    queryKey: eventKey(id),
    queryFn: () => apiGet<OrganizerEvent>(`/api/v1/organizer/events/${id}`),
    // A 404 (unknown id, or another organizer's event — EventController
    // answers both identically) is a real, expected outcome here, not a
    // transient fault. Retrying just delays the not-found state.
    retry: false,
  });
}

export function useCreateEvent() {
  const queryClient = useQueryClient();

  return useMutation<OrganizerEvent, ApiError, CreateEventInput>({
    mutationFn: (input) => apiPost<OrganizerEvent>("/api/v1/organizer/events", input),
    onSuccess: (event) => {
      queryClient.setQueryData(eventKey(event.id), event);
      queryClient.invalidateQueries({ queryKey: EVENTS_KEY });
    },
    retry: false,
  });
}

export function useUpdateEvent(id: string) {
  const queryClient = useQueryClient();

  return useMutation<OrganizerEvent, ApiError, UpdateEventInput>({
    mutationFn: (input) => apiPut<OrganizerEvent>(`/api/v1/organizer/events/${id}`, input),
    onSuccess: (event) => {
      queryClient.setQueryData(eventKey(id), event);
      queryClient.invalidateQueries({ queryKey: EVENTS_KEY });
    },
    retry: false,
  });
}

export function useCancelEvent(id: string) {
  const queryClient = useQueryClient();

  return useMutation<OrganizerEvent, ApiError, void>({
    mutationFn: () => apiPost<OrganizerEvent>(`/api/v1/organizer/events/${id}/cancel`, undefined),
    onSuccess: (event) => {
      queryClient.setQueryData(eventKey(id), event);
      queryClient.invalidateQueries({ queryKey: EVENTS_KEY });
    },
    retry: false,
  });
}

/**
 * SessionController exposes no GET (list or single) — only POST create,
 * PUT update, POST cancel. There is genuinely no endpoint this hook could
 * call to populate a list on page load or refresh, so it isn't a
 * TanStack Query hook at all: the sessions list lives in EventDetailPage's
 * own component state, seeded empty and appended to from each mutation's
 * response. See this feature's report for the backend-gap note — adding
 * the missing GET is out of scope here (constraints: no backend changes).
 */
export function useCreateSession(eventId: string) {
  return useMutation<OrganizerSession, ApiError, CreateSessionInput>({
    mutationFn: (input) =>
      apiPost<OrganizerSession>(`/api/v1/organizer/events/${eventId}/sessions`, input),
    retry: false,
  });
}

export function useCancelSession(eventId: string) {
  return useMutation<OrganizerSession, ApiError, string>({
    mutationFn: (sessionId) =>
      apiPost<OrganizerSession>(
        `/api/v1/organizer/events/${eventId}/sessions/${sessionId}/cancel`,
        undefined,
      ),
    retry: false,
  });
}

export function useArtists(name?: string) {
  const trimmed = name?.trim();
  return useQuery<OrganizerArtist[], ApiError>({
    queryKey: [...ARTISTS_KEY, trimmed ?? ""],
    queryFn: () =>
      apiGet<OrganizerArtist[]>(
        trimmed ? `/api/v1/organizer/artists?name=${encodeURIComponent(trimmed)}` : "/api/v1/organizer/artists",
      ),
  });
}

export function useCreateArtist() {
  const queryClient = useQueryClient();

  return useMutation<OrganizerArtist, ApiError, CreateArtistInput>({
    mutationFn: (input) => apiPost<OrganizerArtist>("/api/v1/organizer/artists", input),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ARTISTS_KEY });
    },
    retry: false,
  });
}
