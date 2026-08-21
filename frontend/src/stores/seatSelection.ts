import { create } from "zustand";
import type { SeatLiveEvent, SeatStatus } from "@/features/booking/types";

interface SeatOverride {
  status: SeatStatus;
  heldByMe: boolean;
  heldUntil: string | null;
}

interface SeatSelectionState {
  /**
   * Live-pushed state per seat, layered on top of the query's snapshot at
   * render time (see SeatMap.tsx) rather than mutating query cache directly
   * — TanStack Query owns the snapshot, this store owns what's changed
   * since. Written from outside React by the SSE handler (or, until
   * inventory-service exists, useSeatLiveUpdates' mock stand-in) — the
   * pattern frontend.md's state-architecture section calls for.
   */
  overrides: Record<string, SeatOverride>;
  applyLiveEvent: (event: SeatLiveEvent, myHeldSeatIds: Set<string>) => void;
  reset: () => void;
}

/**
 * Subscribe with a selector (`useSeatSelectionStore(s => s.overrides[seatId])`),
 * never the whole store — a large seat map re-rendering on every live tick
 * is the one place this app could visibly jank (frontend-product-blueprint.md §15).
 */
export const useSeatSelectionStore = create<SeatSelectionState>((set) => ({
  overrides: {},

  applyLiveEvent: (event, myHeldSeatIds) => {
    set((state) => {
      if (event.type === "hold-expired") {
        // Only meaningful for a seat I'm holding; ignore otherwise.
        if (!myHeldSeatIds.has(event.seatId)) return state;
        return {
          overrides: {
            ...state.overrides,
            [event.seatId]: { status: "AVAILABLE", heldByMe: false, heldUntil: null },
          },
        };
      }

      // seat-updated: never overwrite a seat I'm currently holding — that
      // would mean my own hold request raced its own confirmation echo.
      if (myHeldSeatIds.has(event.seatId)) return state;

      return {
        overrides: {
          ...state.overrides,
          [event.seatId]: { status: event.status, heldByMe: false, heldUntil: null },
        },
      };
    });
  },

  reset: () => set({ overrides: {} }),
}));
