import { beforeEach, describe, expect, it, vi } from "vitest"
import { render, screen } from "@testing-library/react"
import { MemoryRouter, Route, Routes } from "react-router-dom"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"

import { ApiError } from "@/lib/api"
import { EventDetailPage } from "./EventDetailPage"
import type { OrganizerEvent } from "./types"

vi.mock("@/lib/api", async () => {
  const actual = await vi.importActual<typeof import("@/lib/api")>("@/lib/api")
  return { ...actual, apiGet: vi.fn() }
})

import { apiGet } from "@/lib/api"

const EVENT_ID = "11111111-1111-1111-1111-111111111111"

function renderPage(path = `/organizer/events/${EVENT_ID}`) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/organizer/events/:id" element={<EventDetailPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  vi.mocked(apiGet).mockReset()
})

describe("EventDetailPage", () => {
  it("renders a not-found state, not a crash, when the event 404s", async () => {
    vi.mocked(apiGet).mockRejectedValue(new ApiError({ status: 404 }))
    renderPage()

    expect(await screen.findByText(/event not found/i)).toBeInTheDocument()
    expect(screen.getByText(/doesn't exist, or isn't one of yours/i)).toBeInTheDocument()
  })

  it("renders the event's own detail once loaded", async () => {
    const event: OrganizerEvent = {
      id: EVENT_ID,
      venueId: "22222222-2222-2222-2222-222222222222",
      organizerId: "33333333-3333-3333-3333-333333333333",
      title: "Winter Gala",
      description: "A gala.",
      category: "music",
      status: "PUBLISHED",
      region: "us-east",
      createdAt: "2026-01-01T00:00:00Z",
      updatedAt: "2026-01-01T00:00:00Z",
    }
    vi.mocked(apiGet).mockResolvedValue(event)
    renderPage()

    expect(await screen.findByRole("heading", { name: "Winter Gala" })).toBeInTheDocument()
    expect(screen.getByText(/us-east · published/i)).toBeInTheDocument()
    expect(screen.getByRole("button", { name: /^cancel event$/i })).toBeEnabled()
  })

  it("shows a generic error banner for a non-404 failure", async () => {
    vi.mocked(apiGet).mockRejectedValue(new ApiError({ status: 500 }))
    renderPage()

    const alert = await screen.findByRole("alert")
    expect(alert).toHaveTextContent(/could not load this event/i)
  })
})
