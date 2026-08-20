import { useMutation } from "@tanstack/react-query"

import { ApiError, apiPost } from "@/lib/api"
import { useAuthStore, type User } from "@/stores/auth"

interface LoginResponse {
  accessToken: string
  tokenType: string
  expiresIn: number
  user: User
}

/**
 * Note what is NOT in LoginResponse: any refresh token. It arrives as an
 * httpOnly Set-Cookie the browser stores and replays on its own, and this code
 * has no way to read it. That is deliberate — an XSS that can run here still
 * cannot exfiltrate the 30-day credential.
 */
export function useLogin(onSuccess: () => void) {
  const setSession = useAuthStore((state) => state.setSession)

  return useMutation<LoginResponse, ApiError, { email: string; password: string }>({
    mutationFn: (credentials) => apiPost<LoginResponse>("/api/v1/auth/login", credentials),
    onSuccess: (data) => {
      setSession(data.accessToken, data.user)
      onSuccess()
    },
    // No retry. A failed login is a wrong password, and silently re-sending it
    // burns rate-limit budget and lockout attempts the user never authorised.
    retry: false,
  })
}

interface RegisterResponse {
  id: string
  email: string
  roles: string[]
}

/**
 * Registration deliberately does NOT log the user in — the server issues no
 * token here (ADR-012), so there is nothing to store. The user is sent to the
 * login form afterwards.
 */
export function useRegister(onSuccess: () => void) {
  return useMutation<RegisterResponse, ApiError, { email: string; password: string }>({
    mutationFn: (credentials) => apiPost<RegisterResponse>("/api/v1/auth/register", credentials),
    onSuccess: () => onSuccess(),
    retry: false,
  })
}

/**
 * /api/v1/auth/logout is a self-service endpoint being built alongside this
 * (revokes the current session, clears the refresh cookie server-side,
 * returns 204). It may not exist yet, or the request may simply fail on a
 * flaky network — either way the local session still has to go. A user must
 * never be stuck looking "logged in" client-side just because the server
 * call didn't succeed, so `clearSession` runs in `onSettled`, not `onSuccess`,
 * and a failed call is logged rather than surfaced as a blocking error.
 */
export function useLogout(onSettled: () => void) {
  const clearSession = useAuthStore((state) => state.clearSession)

  return useMutation<void, ApiError, void>({
    mutationFn: () => apiPost<void>("/api/v1/auth/logout", undefined),
    onError: (error) => {
      console.error("Logout request failed; clearing local session anyway.", error)
    },
    onSettled: () => {
      clearSession()
      onSettled()
    },
    retry: false,
  })
}
