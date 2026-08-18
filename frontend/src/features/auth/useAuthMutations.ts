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
