import * as React from "react"
import { Link, useParams } from "react-router-dom"
import { Loader2, Ticket } from "lucide-react"

import { Button } from "@/components/ui/button"
import { useHoldSeat, useSeatLiveUpdates, useSeatMap } from "./api"
import { CountdownTimer } from "./components/CountdownTimer"
import { SeatMap } from "./components/SeatMap"

/**
 * frontend-product-blueprint.md §7/§13 — the single most important screen
 * in the product. inventory-service doesn't exist yet (ADR-036 Phase 3);
 * this page is built against src/mocks/handlers.ts's mock contract, which
 * mirrors the real one field-for-field.
 */
export function SeatSelectionPage() {
  const { eventId, sessionId } = useParams<{ eventId: string; sessionId: string }>()
  const seatMap = useSeatMap(sessionId ?? "")
  const holdSeat = useHoldSeat(sessionId ?? "")
  useSeatLiveUpdates(sessionId ?? "", seatMap.data)

  // seatId -> heldUntil, taken directly from each hold's own mutation
  // response — NOT derived from seatMap.data. Waiting on a query refetch
  // to reflect the hold would flash "selected, no timer" until it
  // resolves; the response we already have is the source of truth for
  // MY hold regardless of when the map next refetches.
  const [holds, setHolds] = React.useState<Record<string, string>>({})
  const [holdError, setHoldError] = React.useState<string | null>(null)

  const selectedSeatIds = React.useMemo(() => new Set(Object.keys(holds)), [holds])

  const allSeats = React.useMemo(
    () => seatMap.data?.sections.flatMap((section) => section.seats) ?? [],
    [seatMap.data],
  )
  const priceTiers = seatMap.data?.priceTiers ?? []
  const selectedSeats = allSeats
    .filter((seat) => selectedSeatIds.has(seat.id))
    .map((seat) => ({ ...seat, heldUntil: holds[seat.id] ?? seat.heldUntil }))

  // ADR-002: no release-hold endpoint on this page's contract — a seat
  // I've selected stays HELD in the backend even if I deselect it here,
  // same as adding-then-removing an item never "un-reserves" it early in
  // a real inventory system. Deselecting only changes what I'm about to
  // check out with.
  function handleToggleSeat(seatId: string) {
    setHoldError(null)

    if (selectedSeatIds.has(seatId)) {
      setHolds((prev) => {
        const next = { ...prev }
        delete next[seatId]
        return next
      })
      return
    }

    holdSeat.mutate(seatId, {
      onSuccess: (response) => {
        setHolds((prev) => ({ ...prev, [seatId]: response.heldUntil }))
      },
      onError: (error) => {
        setHoldError(error.message)
      },
    })
  }

  function handleHoldExpire(seatId: string) {
    setHolds((prev) => {
      const next = { ...prev }
      delete next[seatId]
      return next
    })
  }

  const earliestHold = Object.values(holds).sort()[0]

  const totalCents = selectedSeats.reduce((sum, seat) => {
    const tier = priceTiers.find((t) => t.id === seat.priceTierId)
    return sum + (tier?.priceCents ?? 0)
  }, 0)

  return (
    <main className="dashboard-backdrop min-h-dvh px-4 pb-32">
      <div className="mx-auto flex w-full max-w-4xl flex-col gap-6 pt-8">
        <header className="flex items-center justify-between gap-4">
          <div className="flex items-center gap-2 text-lg font-semibold tracking-tight">
            <Ticket aria-hidden="true" className="size-5" />
            TicketMaster
          </div>
          <Link to={`/events/${eventId}`} className="text-sm text-muted-foreground hover:text-foreground">
            Back to event
          </Link>
        </header>

        <div>
          <h1 className="text-2xl font-semibold tracking-tight">Select your seats</h1>
          <p className="text-sm text-muted-foreground">
            Held seats update live as other buyers select them — no need to refresh.
          </p>
        </div>

        {seatMap.isLoading ? (
          <div className="glass-panel flex items-center justify-center gap-2 rounded-xl p-12 text-muted-foreground">
            <Loader2 aria-hidden="true" className="size-4 animate-spin" />
            Loading seat map…
          </div>
        ) : null}

        {seatMap.isError ? (
          <div className="glass-panel rounded-xl p-12 text-center text-muted-foreground">
            Couldn't load the seat map right now. Try again shortly.
          </div>
        ) : null}

        {seatMap.data ? (
          <div className="glass-panel rounded-xl p-6">
            <SeatMap
              sections={seatMap.data.sections}
              priceTiers={seatMap.data.priceTiers}
              selectedSeatIds={selectedSeatIds}
              onToggleSeat={handleToggleSeat}
            />
          </div>
        ) : null}

        {holdError ? (
          <p role="alert" className="text-sm text-destructive">
            {holdError}
          </p>
        ) : null}
      </div>

      {selectedSeats.length > 0 ? (
        <div className="glass-panel fixed inset-x-4 bottom-4 mx-auto flex max-w-4xl flex-wrap items-center justify-between gap-4 rounded-xl p-4 sm:inset-x-0">
          <div className="flex flex-col gap-1">
            <span className="text-sm text-muted-foreground">
              {selectedSeats.length} seat{selectedSeats.length === 1 ? "" : "s"} selected — $
              {(totalCents / 100).toFixed(2)}
            </span>
            {earliestHold ? (
              <span className="text-sm">
                Hold expires in{" "}
                <CountdownTimer
                  until={earliestHold}
                  onExpire={() => {
                    const expiredSeatId = Object.entries(holds).find(([, until]) => until === earliestHold)?.[0]
                    if (expiredSeatId) handleHoldExpire(expiredSeatId)
                  }}
                  className="font-medium tabular-nums data-[urgent=true]:text-destructive"
                />
              </span>
            ) : null}
          </div>
          <Button type="button" disabled title="Checkout isn't built yet — next up.">
            Continue to checkout
          </Button>
        </div>
      ) : null}
    </main>
  )
}
