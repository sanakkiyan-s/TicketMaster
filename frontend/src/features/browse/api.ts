import { useQuery } from "@tanstack/react-query";

import { apiGet, type ApiError } from "@/lib/api";
import type { BrowseEvent } from "./types";

const EVENTS_KEY = ["browse", "events"] as const;

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
