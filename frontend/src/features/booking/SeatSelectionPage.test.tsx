import { beforeEach, describe, expect, it, vi } from "vitest"
import { render, screen } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { MemoryRouter, Route, Routes } from "react-router-dom"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"

import { ApiError } from "@/lib/api"
import { useSeatSelectionStore } from "@/stores/seatSelection"
import { SeatSelectionPage } from "./SeatSelectionPage"
import type { SessionSeatMap } from "./types"

vi.mock("@/lib/api", async () => {
  const actual = await vi.importActual<typeof import("@/lib/api")>("@/lib/api")
  return { ...actual, apiGet: vi.fn(), apiPost: vi.fn() }
})

import { apiGet, apiPost } from "@/lib/api"

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={["/events/event-1/sessions/session-1/seats"]}>
        <Routes>
          <Route path="/events/:eventId/sessions/:sessionId/seats" element={<SeatSelectionPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

const SEAT_MAP: SessionSeatMap = {
  sessionId: "session-1",
  eventId: "event-1",
  priceTiers: [{ id: "tier-floor", label: "Floor", priceCents: 15000 }],
  sections: [
    {
      id: "sec-floor",
      name: "Floor",
      seats: [
        { id: "seat-a1", row: 1, col: 1, priceTierId: "tier-floor", status: "AVAILABLE", heldByMe: false, heldUntil: null },
        { id: "seat-a2", row: 1, col: 2, priceTierId: "tier-floor", status: "PURCHASED", heldByMe: false, heldUntil: null },
      ],
    },
  ],
}

beforeEach(() => {
  vi.mocked(apiGet).mockReset()
  vi.mocked(apiPost).mockReset()
  useSeatSelectionStore.getState().reset()
})

describe("SeatSelectionPage", () => {
  it("shows a loading state before the seat map arrives", () => {
    vi.mocked(apiGet).mockReturnValue(new Promise(() => {}))
    renderPage()

    expect(screen.getByText(/loading seat map/i)).toBeInTheDocument()
  })

  it("renders available and sold seats once the map loads", async () => {
    vi.mocked(apiGet).mockResolvedValue(SEAT_MAP)
    renderPage()

    expect(await screen.findByRole("button", { name: /row 1, seat 1, floor, available/i })).toBeEnabled()
    expect(screen.getByRole("button", { name: /row 1, seat 2, floor, sold/i })).toBeDisabled()
  })

  it("holds a seat and shows a countdown when an available seat is clicked", async () => {
    const user = userEvent.setup()
    vi.mocked(apiGet).mockResolvedValue(SEAT_MAP)
    vi.mocked(apiPost).mockResolvedValue({
      seatId: "seat-a1",
      status: "HELD",
      heldUntil: new Date(Date.now() + 5 * 60_000).toISOString(),
    })
    renderPage()

    const seat = await screen.findByRole("button", { name: /row 1, seat 1, floor, available/i })
    await user.click(seat)

    expect(apiPost).toHaveBeenCalledWith(
      "/api/v1/inventory/sessions/session-1/seats/seat-a1/hold",
      undefined,
    )
    expect(await screen.findByText(/hold expires in/i)).toBeInTheDocument()
    expect(screen.getByRole("timer")).toBeInTheDocument()
  })

  it("shows an error message when the hold request fails", async () => {
    const user = userEvent.setup()
    vi.mocked(apiGet).mockResolvedValue(SEAT_MAP)
    vi.mocked(apiPost).mockRejectedValue(new ApiError({ status: 409, title: "Seat no longer available" }))
    renderPage()

    const seat = await screen.findByRole("button", { name: /row 1, seat 1, floor, available/i })
    await user.click(seat)

    expect(await screen.findByRole("alert")).toHaveTextContent(/seat no longer available/i)
  })
})
