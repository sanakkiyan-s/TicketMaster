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
    // "recovered-token" isn't a real JWT, so decodeAccessTokenClaims can't
    // extract roles from it and user stays null — see the JWT-shaped-token
    // test below for the populated case.
    expect(useAuthStore.getState().user).toBeNull()
  })

  it("backfills id/roles from the access token's claims on success", async () => {
    // header.payload.signature — payload is base64url({"sub":"user:abc-123","roles":["ORGANIZER"]})
    const payload = btoa(JSON.stringify({ sub: "user:abc-123", roles: ["ORGANIZER"] }))
    const jwtShapedToken = `header.${payload}.signature`

    vi.mocked(apiPost).mockResolvedValue({
      accessToken: jwtShapedToken,
      tokenType: "Bearer",
      expiresIn: 600,
    })

    const { result } = renderHook(() => useSilentRefresh())
    await waitFor(() => expect(result.current.isLoading).toBe(false))

    expect(useAuthStore.getState().user).toEqual({ id: "abc-123", roles: ["ORGANIZER"] })
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
