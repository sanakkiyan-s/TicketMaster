import * as React from "react"

import { ApiError, apiPost } from "@/lib/api"
import { decodeAccessTokenClaims } from "@/lib/jwtClaims"
import { useAuthStore } from "@/stores/auth"

interface RefreshResponse {
  accessToken: string
  tokenType: string
  expiresIn: number
}

// Refresh tokens are single-use (RefreshController.java: presenting one
// twice is treated as theft and revokes the whole session family). React.
// StrictMode double-invokes effects in dev, which fires this hook's effect
// twice on mount; without dedupe that sends two /refresh requests with the
// same cookie, and the second one gets treated as a replay and signs the
// user back out. Module-level (not component-level) so it survives the
// mount/unmount/remount StrictMode does, not just re-renders.
let inFlightRefresh: Promise<RefreshResponse> | null = null

function refreshOnce(): Promise<RefreshResponse> {
  if (!inFlightRefresh) {
    inFlightRefresh = apiPost<RefreshResponse>("/api/v1/auth/refresh", undefined).finally(() => {
      inFlightRefresh = null
    })
  }
  return inFlightRefresh
}

/**
 * Runs once at app startup to recover a session after a page reload.
 *
 * The access token lives only in memory (stores/auth.ts's own documented
 * trade-off), so every reload starts with `accessToken: null` even for a
 * signed-in user. The httpOnly refresh cookie survives the reload, so one
 * POST to /refresh — before ProtectedRoute makes its redirect decision — can
 * mint a fresh access token and avoid dropping the session on every reload.
 *
 * RefreshResponse carries no `user`, unlike LoginResponse (see
 * RefreshResponse.java: only accessToken/tokenType/expiresIn). `user.email`
 * is genuinely unrecoverable here — the claims don't carry it — but
 * `user.roles` is (see lib/jwtClaims.ts), and role-gated UI (HomePage's
 * organizer-dashboard card) needs it to survive a reload, not just a fresh
 * login. So `user` is backfilled with claims-derived id/roles and no email
 * (User.email is optional for exactly this reason), rather than left null.
 *
 * A first-time visitor with no refresh cookie gets a 401 here. That is the
 * expected, common case — stay logged out, silently, no error shown.
 */
export function useSilentRefresh(): { isLoading: boolean } {
  const setSession = useAuthStore((state) => state.setSession)
  const [isLoading, setIsLoading] = React.useState(true)

  React.useEffect(() => {
    let cancelled = false

    refreshOnce()
      .then((data) => {
        if (cancelled) return
        const claims = decodeAccessTokenClaims(data.accessToken)
        setSession(data.accessToken, claims ? { id: claims.userId, roles: claims.roles } : null)
      })
      .catch((error: unknown) => {
        // A 401 (no cookie, expired, revoked, replayed) is the normal
        // outcome for anyone without an active session — not an error
        // worth logging. Anything else (network failure, 5xx) still must
        // not block the app; it's logged for visibility and swallowed the
        // same way, since staying logged out is always a safe fallback.
        const isExpectedUnauthenticated = error instanceof ApiError && error.status === 401
        if (!isExpectedUnauthenticated) {
          console.error("Silent refresh failed", error)
        }
      })
      .finally(() => {
        if (!cancelled) setIsLoading(false)
      })

    return () => {
      cancelled = true
    }
    // Intentionally runs once: this is an app-startup check, not a
    // resubscription that should re-run when setSession's identity changes.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  return { isLoading }
}
