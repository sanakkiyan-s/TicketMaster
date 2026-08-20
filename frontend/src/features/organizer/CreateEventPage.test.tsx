import { beforeEach, describe, expect, it, vi } from "vitest"
import { render, screen } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { MemoryRouter, Route, Routes } from "react-router-dom"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"

import { ApiError } from "@/lib/api"
import { CreateEventPage } from "./CreateEventPage"
import type { OrganizerEvent } from "./types"

vi.mock("@/lib/api", async () => {
  const actual = await vi.importActual<typeof import("@/lib/api")>("@/lib/api")
  return { ...actual, apiPost: vi.fn() }
})

import { apiPost } from "@/lib/api"

function renderPage() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={["/organizer/events/new"]}>
        <Routes>
          <Route path="/organizer/events/new" element={<CreateEventPage />} />
          <Route path="/organizer/events/:id" element={<div>Detail page for event</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

const VALID_VENUE_ID = "22222222-2222-2222-2222-222222222222"

async function fillValidForm() {
  const user = userEvent.setup()
  await user.type(screen.getByLabelText(/venue id/i), VALID_VENUE_ID)
  await user.type(screen.getByLabelText(/title/i), "Winter Gala")
  await user.type(screen.getByLabelText(/^region/i), "us-east")
  return user
}

beforeEach(() => {
  vi.mocked(apiPost).mockReset()
})

describe("CreateEventPage validation", () => {
  it("shows client-side errors for required fields on submit without touching the network", async () => {
    renderPage()
    const user = userEvent.setup()

    await user.click(screen.getByRole("button", { name: /create event/i }))

    expect(await screen.findByText(/venue id is required/i)).toBeInTheDocument()
    expect(screen.getByText(/title is required/i)).toBeInTheDocument()
    expect(screen.getByText(/region is required/i)).toBeInTheDocument()
    expect(apiPost).not.toHaveBeenCalled()
  })

  it("rejects a venueId that isn't a UUID before submitting", async () => {
    renderPage()
    const user = userEvent.setup()

    await user.type(screen.getByLabelText(/venue id/i), "not-a-uuid")
    await user.type(screen.getByLabelText(/title/i), "Winter Gala")
    await user.type(screen.getByLabelText(/^region/i), "us-east")
    await user.click(screen.getByRole("button", { name: /create event/i }))

    expect(await screen.findByText(/must be a valid uuid/i)).toBeInTheDocument()
    expect(apiPost).not.toHaveBeenCalled()
  })

  it("renders the backend's per-field validation errors from a 400 ProblemDetail", async () => {
    vi.mocked(apiPost).mockRejectedValue(
      new ApiError({ status: 400, title: "Validation failed", errors: { title: "must not be blank" } }),
    )
    renderPage()
    await fillValidForm()

    await userEvent.click(screen.getByRole("button", { name: /create event/i }))

    expect(await screen.findByText("must not be blank")).toBeInTheDocument()
  })

  it("submits and redirects to the new event's detail page on success", async () => {
    const created: OrganizerEvent = {
      id: "11111111-1111-1111-1111-111111111111",
      venueId: VALID_VENUE_ID,
      organizerId: "33333333-3333-3333-3333-333333333333",
      title: "Winter Gala",
      description: null,
      category: null,
      status: "DRAFT",
      region: "us-east",
      createdAt: "2026-01-01T00:00:00Z",
      updatedAt: "2026-01-01T00:00:00Z",
    }
    vi.mocked(apiPost).mockResolvedValue(created)
    renderPage()
    await fillValidForm()

    await userEvent.click(screen.getByRole("button", { name: /create event/i }))

    expect(await screen.findByText("Detail page for event")).toBeInTheDocument()
    expect(apiPost).toHaveBeenCalledWith(
      "/api/v1/organizer/events",
      expect.objectContaining({ title: "Winter Gala", region: "us-east", venueId: VALID_VENUE_ID }),
    )
  })
})
