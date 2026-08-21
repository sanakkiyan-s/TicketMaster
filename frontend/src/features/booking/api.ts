import * as React from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { apiGet, apiPost, type ApiError } from "@/lib/api";
import { useSeatSelectionStore } from "@/stores/seatSelection";
import type { HoldResponse, SeatLiveEvent, SessionSeatMap } from "./types";

const seatMapKey = (sessionId: string) => ["booking", "seatMap", sessionId] as const;

export function useSeatMap(sessionId: string) {
  return useQuery<SessionSeatMap, ApiError>({
    queryKey: seatMapKey(sessionId),
    queryFn: () => apiGet<SessionSeatMap>(`/api/v1/inventory/sessions/${sessionId}/seats`),
    // The map itself changes slowly; live occupancy comes from
    // useSeatLiveUpdates, not refetch-on-focus fighting that stream.
    refetchOnWindowFocus: false,
  });
}

export function useHoldSeat(sessionId: string) {
  const queryClient = useQueryClient();

  return useMutation<HoldResponse, ApiError, string>({
    mutationFn: (seatId) =>
      apiPost<HoldResponse>(`/api/v1/inventory/sessions/${sessionId}/seats/${seatId}/hold`, undefined),
    onSuccess: () => {
      // Re-fetch rather than hand-patch the cache: a hold can fail with a
      // response that still needs a snapshot refresh (seat already gone).
      queryClient.invalidateQueries({ queryKey: seatMapKey(sessionId) });
    },
    retry: false,
  });
}

/**
 * ADR-022's real mechanism is Server-Sent Events against inventory-service
 * (frontend-product-blueprint.md §8) — that service doesn't exist yet
 * (ADR-036 Phase 3). This is a mock stand-in, not a real EventSource: it
 * fabricates occasional "another buyer took a seat" events so the seat map
 * demonstrably updates live, matching the real event shapes exactly
 * (SeatLiveEvent) so swapping this hook's body for a real
 * `new EventSource(...)` later touches no caller.
 */
export function useSeatLiveUpdates(sessionId: string, seatMap: SessionSeatMap | undefined): void {
  const applyLiveEvent = useSeatSelectionStore((s) => s.applyLiveEvent);

  React.useEffect(() => {
    if (!seatMap) return;

    const allSeats = seatMap.sections.flatMap((section) => section.seats);

    const interval = window.setInterval(() => {
      const candidates = allSeats.filter((seat) => seat.status === "AVAILABLE");
      if (candidates.length === 0) return;

      const target = candidates[Math.floor(Math.random() * candidates.length)];
      const event: SeatLiveEvent = { type: "seat-updated", seatId: target.id, status: "HELD" };
      // myHeldSeatIds is empty here deliberately — this mock only ever
      // simulates OTHER buyers, never touches a seat the current user holds.
      applyLiveEvent(event, new Set());
    }, 4000);

    return () => window.clearInterval(interval);
  }, [sessionId, seatMap, applyLiveEvent]);
}
