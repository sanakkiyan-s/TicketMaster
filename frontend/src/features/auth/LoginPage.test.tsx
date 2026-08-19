import { beforeEach, describe, expect, it, vi } from "vitest"
import { render, screen } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { MemoryRouter } from "react-router-dom"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"

import { ApiError } from "@/lib/api"
import { LoginPage } from "./LoginPage"

vi.mock("@/lib/api", async () => {
  const actual = await vi.importActual<typeof import("@/lib/api")>("@/lib/api")
  return { ...actual, apiPost: vi.fn() }
})

import { apiPost } from "@/lib/api"

function renderLoginPage() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={["/login"]}>
        <LoginPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

async function submitLoginForm() {
  const user = userEvent.setup()
  await user.type(screen.getByLabelText("Email"), "test@example.com")
  await user.type(screen.getByLabelText("Password"), "correct-horse-battery")
  await user.click(screen.getByRole("button", { name: /sign in/i }))
}

beforeEach(() => {
  vi.mocked(apiPost).mockReset()
})

describe("LoginPage error messaging", () => {
  it("shows one generic message on 401, regardless of the real cause", async () => {
    vi.mocked(apiPost).mockRejectedValue(new ApiError({ status: 401 }))
    renderLoginPage()

    await submitLoginForm()

    const alert = await screen.findByRole("alert")
    expect(alert).toHaveTextContent(
      "That email and password combination is not correct.",
    )
  })

  it("shows a distinct rate-limit message on 429 with no specific retry time", async () => {
    vi.mocked(apiPost).mockRejectedValue(new ApiError({ status: 429 }))
    renderLoginPage()

    await submitLoginForm()

    const alert = await screen.findByRole("alert")
    expect(alert).toHaveTextContent(/too many sign-in attempts/i)
    // No fabricated countdown — the backend response carries no
    // Retry-After header or body field to build one from.
    expect(alert.textContent).not.toMatch(/\d/)
  })
})
