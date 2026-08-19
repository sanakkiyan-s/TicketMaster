import { Navigate, Outlet, useLocation } from "react-router-dom"

import { useAuthStore } from "@/stores/auth"

/**
 * Gate for authenticated routes.
 *
 * A UX control, NOT a security control. The token is in memory and this check
 * runs in the browser, so anyone can bypass it with devtools — and it does not
 * matter, because the API rejects unauthenticated requests regardless. This
 * only spares a user the experience of loading a page that will fail.
 *
 * `state={{ from }}` and `replace` together mean a redirected user can be sent
 * back where they were going after signing in, and Back does not bounce them
 * into the login page they just left.
 */
export function ProtectedRoute() {
  const accessToken = useAuthStore((state) => state.accessToken)
  const location = useLocation()

  if (!accessToken) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />
  }

  return <Outlet />
}
