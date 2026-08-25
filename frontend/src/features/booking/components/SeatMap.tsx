import * as React from "react"

import { useSeatSelectionStore } from "@/stores/seatSelection"
import { Seat } from "./Seat"
import type { PriceTier, Seat as SeatModel, SeatSection } from "../types"

interface SeatMapProps {
  sections: SeatSection[]
  priceTiers: PriceTier[]
  selectedSeatIds: Set<string>
  onToggleSeat: (seatId: string) => void
}

export function SeatMap({ sections, priceTiers, selectedSeatIds, onToggleSeat }: SeatMapProps) {
  const tierIndexById = React.useMemo(() => {
    const map = new Map<string, number>()
    priceTiers.forEach((tier, index) => map.set(tier.id, index))
    return map
  }, [priceTiers])

  return (
    <div className="flex flex-col gap-8">
      {sections.map((section) => (
        <div key={section.id} className="flex flex-col gap-3">
          <h3 className="text-sm font-medium tracking-wide text-muted-foreground uppercase">
            {section.name}
          </h3>
          <SeatGrid
            seats={section.seats}
            priceTiers={priceTiers}
            tierIndexById={tierIndexById}
            selectedSeatIds={selectedSeatIds}
            onToggleSeat={onToggleSeat}
          />
        </div>
      ))}

      <ul className="flex flex-wrap gap-x-6 gap-y-2 text-sm text-muted-foreground">
        {priceTiers.map((tier, index) => (
          <li key={tier.id} className="flex items-center gap-2">
            <span
              aria-hidden="true"
              className="booking-seat-legend-swatch"
              style={{ "--tier-index": index } as React.CSSProperties}
            />
            {tier.label} — ${(tier.priceCents / 100).toFixed(2)}
          </li>
        ))}
      </ul>
    </div>
  )
}

function SeatGrid({
  seats,
  priceTiers,
  tierIndexById,
  selectedSeatIds,
  onToggleSeat,
}: {
  seats: SeatModel[]
  priceTiers: PriceTier[]
  tierIndexById: Map<string, number>
  selectedSeatIds: Set<string>
  onToggleSeat: (seatId: string) => void
}) {
  const rows = React.useMemo(() => {
    const byRow = new Map<number, SeatModel[]>()
    for (const seat of seats) {
      const row = byRow.get(seat.row) ?? []
      row.push(seat)
      byRow.set(seat.row, row)
    }
    return [...byRow.entries()].sort(([a], [b]) => a - b)
  }, [seats])

  return (
    <div className="flex flex-col gap-1.5">
      {rows.map(([rowNumber, rowSeats]) => (
        <div key={rowNumber} className="flex items-center gap-1.5">
          <span className="w-5 shrink-0 text-right text-xs text-muted-foreground">{rowNumber}</span>
          <div className="flex gap-1.5">
            {[...rowSeats]
              .sort((a, b) => a.col - b.col)
              .map((seat) => {
                const tier = priceTiers.find((t) => t.id === seat.priceTierId)
                if (!tier) return null
                return (
                  <SeatCell
                    key={seat.id}
                    baseSeat={seat}
                    tier={tier}
                    tierIndex={tierIndexById.get(tier.id) ?? 0}
                    selected={selectedSeatIds.has(seat.id)}
                    onSelect={onToggleSeat}
                  />
                )
              })}
          </div>
        </div>
      ))}
    </div>
  )
}

/**
 * The one place a live override (SSE-pushed, or the mock stand-in in
 * useSeatLiveUpdates) gets merged onto the query snapshot — subscribed
 * with a selector scoped to this seat's id, so a push affecting one seat
 * only re-renders that seat's cell, never the whole map
 * (frontend-product-blueprint.md §15).
 */
function SeatCell({
  baseSeat,
  tier,
  tierIndex,
  selected,
  onSelect,
}: {
  baseSeat: SeatModel
  tier: PriceTier
  tierIndex: number
  selected: boolean
  onSelect: (seatId: string) => void
}) {
  const override = useSeatSelectionStore((s) => s.overrides[baseSeat.id])
  const seat = override ? { ...baseSeat, ...override } : baseSeat

  return <Seat seat={seat} tier={tier} tierIndex={tierIndex} selected={selected} onSelect={onSelect} />
}
