/**
 * Mirrors ADR-002-seat-locking-strategy.md's actual stored states exactly
 * — AVAILABLE, HELD, PURCHASED only. No EXPIRED value: a hold flips
 * straight back to AVAILABLE once held_until passes (sweep or lazy-on-
 * read), per that ADR's pseudocode — see frontend-product-blueprint.md
 * §7's note on this. inventory-service doesn't exist yet (ADR-036 Phase
 * 3, not built); these types are the target contract this page is built
 * against via mocks (src/mocks/handlers.ts), swapped for real calls with
 * no shape change once that service lands.
 */
export type SeatStatus = "AVAILABLE" | "HELD" | "PURCHASED";

export interface Seat {
  id: string;
  row: number;
  col: number;
  priceTierId: string;
  status: SeatStatus;
  /** Only meaningful when status === "HELD" and it's this browser's own hold. */
  heldByMe: boolean;
  /** ISO timestamp. Only present when heldByMe — the countdown source (ADR-002: client-computed, no server tick). */
  heldUntil: string | null;
}

export interface PriceTier {
  id: string;
  label: string;
  priceCents: number;
}

export interface SeatSection {
  id: string;
  name: string;
  seats: Seat[];
}

export interface SessionSeatMap {
  sessionId: string;
  eventId: string;
  priceTiers: PriceTier[];
  sections: SeatSection[];
}

export interface HoldResponse {
  seatId: string;
  status: "HELD";
  /** ISO timestamp — ADR-002's flat 5-minute base hold. */
  heldUntil: string;
}

/** ADR-002/ADR-022's two live event shapes, delivered over SSE in the real system. */
export type SeatLiveEvent =
  | { type: "seat-updated"; seatId: string; status: SeatStatus }
  | { type: "hold-expired"; seatId: string };
