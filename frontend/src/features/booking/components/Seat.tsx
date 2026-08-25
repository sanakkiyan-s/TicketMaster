import * as React from "react"

import type { PriceTier, Seat as SeatModel } from "../types"

interface SeatProps {
  seat: SeatModel
  tier: PriceTier
  tierIndex: number
  selected: boolean
  onSelect: (seatId: string) => void
}

const STATUS_LABEL: Record<SeatModel["status"], string> = {
  AVAILABLE: "available",
  HELD: "temporarily unavailable",
  PURCHASED: "sold",
}

/**
 * Plain CSS keyed on [data-status]/[data-mine], not utility classes — a
 * live seat-status flip (SSE today via useSeatLiveUpdates' mock stand-in,
 * a real EventSource once inventory-service exists) should flip an
 * attribute, not churn class strings, per frontend-product-blueprint.md §14.
 */
export function Seat({ seat, tier, tierIndex, selected, onSelect }: SeatProps) {
  const isSelectable = seat.status === "AVAILABLE" || (seat.status === "HELD" && seat.heldByMe)

  const label = seat.heldByMe
    ? `Row ${seat.row}, seat ${seat.col}, ${tier.label}, held by you`
    : `Row ${seat.row}, seat ${seat.col}, ${tier.label}, ${STATUS_LABEL[seat.status]}`

  return (
    <button
      type="button"
      className="booking-seat"
      data-status={seat.status}
      data-mine={seat.heldByMe ? "true" : undefined}
      data-selected={selected ? "true" : undefined}
      style={{ "--tier-index": tierIndex } as React.CSSProperties}
      disabled={!isSelectable}
      aria-label={label}
      aria-pressed={selected}
      onClick={() => onSelect(seat.id)}
    />
  )
}
