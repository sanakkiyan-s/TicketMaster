import { beforeEach, describe, expect, it, vi } from "vitest"
import { renderHook, waitFor } from "@testing-library/react"

import { ApiError } from "@/lib/api"
import { useAuthStore } from "@/stores/auth"
import { useSilentRefresh } from "./useSilentRefresh"

vi.mock("@/lib/api", async () => {
  const actual = await vi.importActual<typeof import("@/lib/api")>("@/lib/api")
  return { ...actual, apiPost: vi.fn() }
})

import { apiPost } from "@/lib/api"

beforeEach(() => {
  useAuthStore.setState({ accessToken: null, user: null })
  vi.mocked(apiPost).mockReset()
})

describe("useSilentRefresh", () => {
  it("populates the session on success", async () => {
    vi.mocked(apiPost).mockResolvedValue({
      accessToken: "recovered-token",
      tokenType: "Bearer",
      expiresIn: 600,
    })

    const { result } = renderHook(() => useSilentRefresh())

    expect(result.current.isLoading).toBe(true)
    await waitFor(() => expect(result.current.isLoading).toBe(false))

    expect(apiPost).toHaveBeenCalledWith("/api/v1/auth/refresh", undefined)
    expect(useAuthStore.getState().accessToken).toBe("recovered-token")
    // RefreshResponse carries no user object — user stays null rather than
    // being fabricated from JWT claims that don't include an email.
    expect(useAuthStore.getState().user).toBeNull()
  })

  it("stays logged out, without throwing, when refresh fails", async () => {
    vi.mocked(apiPost).mockRejectedValue(new ApiError({ status: 401 }))

    const { result } = renderHook(() => useSilentRefresh())

    await waitFor(() => expect(result.current.isLoading).toBe(false))

    expect(useAuthStore.getState().accessToken).toBeNull()
    expect(useAuthStore.getState().user).toBeNull()
  })

  it("also stays logged out on an unexpected error (e.g. network failure)", async () => {
    vi.mocked(apiPost).mockRejectedValue(new ApiError({ status: 502 }))

    const { result } = renderHook(() => useSilentRefresh())

    await waitFor(() => expect(result.current.isLoading).toBe(false))

    expect(useAuthStore.getState().accessToken).toBeNull()
  })
})
