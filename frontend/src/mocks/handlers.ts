import { http, HttpResponse } from "msw";

import type { PriceTier, Seat, SessionSeatMap } from "@/features/booking/types";

/**
 * inventory-service doesn't exist yet (ADR-036 Phase 3, not built). These
 * handlers stand in for it during frontend design work — every response
 * shape matches src/features/booking/types.ts exactly, which itself
 * mirrors ADR-002/ADR-022's real contract, so removing this file and
 * pointing at the real service later touches no component or hook.
 *
 * State is kept in-memory per sessionId (module scope, reset on reload)
 * so a hold placed via POST is reflected on the next GET — a static
 * fixture would make the "someone else already grabbed this seat" case
 * impossible to see.
 */

const PRICE_TIERS: PriceTier[] = [
  { id: "tier-floor", label: "Floor", priceCents: 15000 },
  { id: "tier-lower", label: "Lower Bowl", priceCents: 9500 },
  { id: "tier-upper", label: "Upper Bowl", priceCents: 5500 },
];

const SECTION_LAYOUT = [
  { id: "sec-floor", name: "Floor", tierId: "tier-floor", rows: 3, seatsPerRow: 10 },
  { id: "sec-lower", name: "Lower Bowl", tierId: "tier-lower", rows: 5, seatsPerRow: 14 },
  { id: "sec-upper", name: "Upper Bowl", tierId: "tier-upper", rows: 6, seatsPerRow: 16 },
];

// Deterministic per sessionId so reloading the same URL shows the same
// layout, without needing a real seeded database.
function seededRandom(seed: string) {
  let h = 0;
  for (let i = 0; i < seed.length; i++) h = (Math.imul(31, h) + seed.charCodeAt(i)) | 0;
  return () => {
    h = (Math.imul(h, 48271) + 1) % 0x7fffffff;
    return h / 0x7fffffff;
  };
}

function buildSeatMap(sessionId: string): SessionSeatMap {
  const random = seededRandom(sessionId);
  const sections = SECTION_LAYOUT.map((layout) => {
    const seats: Seat[] = [];
    for (let row = 1; row <= layout.rows; row++) {
      for (let col = 1; col <= layout.seatsPerRow; col++) {
        // ~12% pre-sold so the map doesn't open on an implausible clean slate.
        const status = random() < 0.12 ? "PURCHASED" : "AVAILABLE";
        seats.push({
          id: `${layout.id}-r${row}c${col}`,
          row,
          col,
          priceTierId: layout.tierId,
          status,
          heldByMe: false,
          heldUntil: null,
        });
      }
    }
    return { id: layout.id, name: layout.name, seats };
  });

  return { sessionId, eventId: "mock-event", priceTiers: PRICE_TIERS, sections };
}

const seatMapsBySession = new Map<string, SessionSeatMap>();

function getOrCreateSeatMap(sessionId: string): SessionSeatMap {
  let map = seatMapsBySession.get(sessionId);
  if (!map) {
    map = buildSeatMap(sessionId);
    seatMapsBySession.set(sessionId, map);
  }
  return map;
}

export const handlers = [
  http.get("/api/v1/inventory/sessions/:sessionId/seats", ({ params }) => {
    const sessionId = String(params.sessionId);
    return HttpResponse.json(getOrCreateSeatMap(sessionId));
  }),

  http.post("/api/v1/inventory/sessions/:sessionId/seats/:seatId/hold", ({ params }) => {
    const sessionId = String(params.sessionId);
    const seatId = String(params.seatId);
    const map = getOrCreateSeatMap(sessionId);
    const seat = map.sections.flatMap((s) => s.seats).find((s) => s.id === seatId);

    if (!seat || seat.status !== "AVAILABLE") {
      // Matches ADR-002's real failure mode: someone else got there first.
      return HttpResponse.json(
        { status: 409, title: "Seat no longer available", detail: `${seatId} is no longer AVAILABLE.` },
        { status: 409 },
      );
    }

    const heldUntil = new Date(Date.now() + 5 * 60_000).toISOString();
    seat.status = "HELD";
    seat.heldByMe = true;
    seat.heldUntil = heldUntil;

    return HttpResponse.json({ seatId, status: "HELD", heldUntil });
  }),
];
