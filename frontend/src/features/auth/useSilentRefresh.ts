import * as React from "react"

import { ApiError, apiPost } from "@/lib/api"
import { useAuthStore } from "@/stores/auth"

interface RefreshResponse {
  accessToken: string
  tokenType: string
  expiresIn: number
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
 * RefreshResponse.java: only accessToken/tokenType/expiresIn). Decoding the
 * access token's claims client-side was considered as a way to backfill
 * `user` — the codebase already treats JWT claims as informational rather
 * than a trust boundary in the browser — but the claims (`sub` as
 * `user:<uuid>`, `roles`) do not include email, so any User reconstructed
 * that way would need a fabricated email field. That's worse than leaving
 * `user` null: every consumer of the store already tolerates `user` being
 * null (ProtectedRoute only checks `accessToken`), so this is the less
 * invasive choice. `user` is populated for real on the next login.
 *
 * A first-time visitor with no refresh cookie gets a 401 here. That is the
 * expected, common case — stay logged out, silently, no error shown.
 */
export function useSilentRefresh(): { isLoading: boolean } {
  const setSession = useAuthStore((state) => state.setSession)
  const [isLoading, setIsLoading] = React.useState(true)

  React.useEffect(() => {
    let cancelled = false

    apiPost<RefreshResponse>("/api/v1/auth/refresh", undefined)
      .then((data) => {
        if (cancelled) return
        setSession(data.accessToken, null)
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
