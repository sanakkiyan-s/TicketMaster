import type { ReactNode } from "react"
import { beforeEach, describe, expect, it, vi } from "vitest"
import { act, renderHook, waitFor } from "@testing-library/react"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"

import { ApiError } from "@/lib/api"
import { useAuthStore } from "@/stores/auth"
import { useLogout } from "./useAuthMutations"

vi.mock("@/lib/api", async () => {
  const actual = await vi.importActual<typeof import("@/lib/api")>("@/lib/api")
  return { ...actual, apiPost: vi.fn() }
})

import { apiPost } from "@/lib/api"

function wrapper({ children }: { children: ReactNode }) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>
}

beforeEach(() => {
  useAuthStore.setState({
    accessToken: "existing-token",
    user: { id: "u1", email: "a@b.com", roles: [] },
  })
  vi.mocked(apiPost).mockReset()
})

describe("useLogout", () => {
  it("clears the local session even when the network call fails", async () => {
    vi.mocked(apiPost).mockRejectedValue(new ApiError({ status: 404 }))
    const onSettled = vi.fn()

    const { result } = renderHook(() => useLogout(onSettled), { wrapper })

    act(() => {
      result.current.mutate()
    })

    await waitFor(() => expect(onSettled).toHaveBeenCalledTimes(1))

    expect(useAuthStore.getState().accessToken).toBeNull()
    expect(useAuthStore.getState().user).toBeNull()
  })

  it("clears the local session on a successful logout too", async () => {
    vi.mocked(apiPost).mockResolvedValue(undefined)
    const onSettled = vi.fn()

    const { result } = renderHook(() => useLogout(onSettled), { wrapper })

    act(() => {
      result.current.mutate()
    })

    await waitFor(() => expect(onSettled).toHaveBeenCalledTimes(1))

    expect(useAuthStore.getState().accessToken).toBeNull()
    expect(useAuthStore.getState().user).toBeNull()
  })
})
