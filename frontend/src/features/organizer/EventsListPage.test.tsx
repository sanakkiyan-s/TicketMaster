import { beforeEach, describe, expect, it, vi } from "vitest"
import { render, screen } from "@testing-library/react"
import { MemoryRouter } from "react-router-dom"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"

import { ApiError } from "@/lib/api"
import { EventsListPage } from "./EventsListPage"
import type { OrganizerEvent } from "./types"

vi.mock("@/lib/api", async () => {
  const actual = await vi.importActual<typeof import("@/lib/api")>("@/lib/api")
  return { ...actual, apiGet: vi.fn() }
})

import { apiGet } from "@/lib/api"

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={["/organizer/events"]}>
        <EventsListPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

const EVENT: OrganizerEvent = {
  id: "11111111-1111-1111-1111-111111111111",
  venueId: "22222222-2222-2222-2222-222222222222",
  organizerId: "33333333-3333-3333-3333-333333333333",
  title: "Winter Gala",
  description: null,
  category: null,
  status: "PUBLISHED",
  region: "us-east",
  createdAt: "2026-01-01T00:00:00Z",
  updatedAt: "2026-01-01T00:00:00Z",
}

beforeEach(() => {
  vi.mocked(apiGet).mockReset()
})

describe("EventsListPage", () => {
  it("shows a loading state before data arrives", () => {
    vi.mocked(apiGet).mockReturnValue(new Promise(() => {}))
    renderPage()

    expect(screen.getByText(/loading events/i)).toBeInTheDocument()
  })

  it("shows an empty state with a create-event link when there are no events", async () => {
    vi.mocked(apiGet).mockResolvedValue([])
    renderPage()

    expect(await screen.findByText(/no events yet/i)).toBeInTheDocument()
    expect(screen.getByRole("link", { name: /create event/i })).toHaveAttribute(
      "href",
      "/organizer/events/new",
    )
  })

  it("renders each event's title, region, and status when populated", async () => {
    vi.mocked(apiGet).mockResolvedValue([EVENT])
    renderPage()

    expect(await screen.findByText("Winter Gala")).toBeInTheDocument()
    expect(screen.getByText("us-east")).toBeInTheDocument()
    expect(screen.getByText("PUBLISHED")).toBeInTheDocument()
    expect(screen.getByRole("link", { name: /winter gala/i })).toHaveAttribute(
      "href",
      `/organizer/events/${EVENT.id}`,
    )
  })

  it("shows an error banner when the request fails", async () => {
    vi.mocked(apiGet).mockRejectedValue(new ApiError({ status: 500 }))
    renderPage()

    const alert = await screen.findByRole("alert")
    expect(alert).toHaveTextContent(/could not load your events/i)
  })
})
