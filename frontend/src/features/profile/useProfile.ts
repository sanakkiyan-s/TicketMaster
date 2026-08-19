import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"

import { apiGet, apiPut, type ApiError } from "@/lib/api"

export interface Profile {
  userId: string
  displayName: string | null
  phoneNumber: string | null
  avatarUrl: string | null
  createdAt: string
  updatedAt: string
}

export interface Preferences {
  emailOptIn: boolean
  smsOptIn: boolean
}

const PROFILE_KEY = ["profile"] as const
const PREFERENCES_KEY = ["preferences"] as const

export function useProfile() {
  return useQuery<Profile, ApiError>({
    queryKey: PROFILE_KEY,
    queryFn: () => apiGet<Profile>("/api/v1/users/me"),
  })
}

export function useUpdateProfile() {
  const queryClient = useQueryClient()

  return useMutation<
    Profile,
    ApiError,
    { displayName?: string; phoneNumber?: string; avatarUrl?: string }
  >({
    mutationFn: (update) => apiPut<Profile>("/api/v1/users/me", update),
    onSuccess: (profile) => {
      queryClient.setQueryData(PROFILE_KEY, profile)
    },
  })
}

export function usePreferences() {
  return useQuery<Preferences, ApiError>({
    queryKey: PREFERENCES_KEY,
    queryFn: () => apiGet<Preferences>("/api/v1/users/me/preferences"),
  })
}

export function useUpdatePreferences() {
  const queryClient = useQueryClient()

  return useMutation<Preferences, ApiError, Preferences>({
    mutationFn: (update) => apiPut<Preferences>("/api/v1/users/me/preferences", update),
    onSuccess: (preferences) => {
      queryClient.setQueryData(PREFERENCES_KEY, preferences)
    },
  })
}
