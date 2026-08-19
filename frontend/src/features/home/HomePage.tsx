import * as React from "react"
import { Loader2, LogOut, Ticket, User as UserIcon } from "lucide-react"

import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { useLogout } from "@/features/auth/useAuthMutations"
import { usePreferences, useProfile, useUpdatePreferences, useUpdateProfile } from "@/features/profile/useProfile"
import { useAuthStore } from "@/stores/auth"

/**
 * First authenticated surface, replacing the old bare Placeholder/Account
 * pair. Fetches real profile + preferences from user-service (via the
 * gateway's new /api/v1/users/** route) rather than showing an empty shell —
 * there was nothing on screen before because nothing was ever fetched.
 */
export function HomePage() {
  const user = useAuthStore((state) => state.user)
  const logout = useLogout(() => {
    // Session clears via useAuthStore regardless of route; no redirect
    // needed here since "/" already renders the signed-out landing state.
  })

  const profile = useProfile()
  const preferences = usePreferences()

  return (
    <main className="auth-backdrop min-h-dvh px-4 py-10">
      <div className="mx-auto flex w-full max-w-2xl flex-col gap-6">
        <header className="flex items-center justify-between">
          <div className="flex items-center gap-2 text-lg font-semibold tracking-tight">
            <Ticket aria-hidden="true" className="size-5" />
            TicketMaster
          </div>
          <Button
            type="button"
            variant="outline"
            size="sm"
            disabled={logout.isPending}
            onClick={() => logout.mutate()}
          >
            {logout.isPending ? (
              <Loader2 aria-hidden="true" className="animate-spin" />
            ) : (
              <LogOut aria-hidden="true" />
            )}
            Sign out
          </Button>
        </header>

        <Card>
          <CardHeader>
            <CardTitle>
              Welcome{profile.data?.displayName ? `, ${profile.data.displayName}` : ""}
            </CardTitle>
            <CardDescription>
              {user?.email ?? "Signed in"} · Events and bookings arrive here once
              event-service and booking-service come online.
            </CardDescription>
          </CardHeader>
        </Card>

        <ProfileCard />
        <PreferencesCard preferences={preferences} />
      </div>
    </main>
  )
}

function ProfileCard() {
  const profile = useProfile()
  const updateProfile = useUpdateProfile()

  const [displayName, setDisplayName] = React.useState("")
  const [editing, setEditing] = React.useState(false)

  React.useEffect(() => {
    if (profile.data && !editing) {
      setDisplayName(profile.data.displayName ?? "")
    }
  }, [profile.data, editing])

  function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    updateProfile.mutate(
      { displayName: displayName.trim() },
      { onSuccess: () => setEditing(false) },
    )
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-lg">
          <UserIcon aria-hidden="true" className="size-4" />
          Profile
        </CardTitle>
        <CardDescription>Your display name, shown on bookings and tickets.</CardDescription>
      </CardHeader>
      <CardContent>
        {profile.isLoading ? (
          <p className="text-sm text-muted-foreground">Loading profile…</p>
        ) : profile.isError ? (
          <p role="alert" className="text-sm font-medium text-destructive">
            Could not load your profile. {profile.error.message}
          </p>
        ) : editing ? (
          <form onSubmit={handleSubmit} className="flex flex-col gap-3">
            <div className="flex flex-col gap-2">
              <Label htmlFor="displayName">Display name</Label>
              <Input
                id="displayName"
                value={displayName}
                onChange={(event) => setDisplayName(event.target.value)}
                maxLength={255}
                autoFocus
              />
            </div>
            {updateProfile.isError ? (
              <p role="alert" className="text-sm font-medium text-destructive">
                {updateProfile.error.message}
              </p>
            ) : null}
            <div className="flex gap-2">
              <Button type="submit" size="sm" disabled={updateProfile.isPending}>
                {updateProfile.isPending ? (
                  <Loader2 aria-hidden="true" className="animate-spin" />
                ) : (
                  "Save"
                )}
              </Button>
              <Button
                type="button"
                variant="ghost"
                size="sm"
                disabled={updateProfile.isPending}
                onClick={() => setEditing(false)}
              >
                Cancel
              </Button>
            </div>
          </form>
        ) : (
          <div className="flex items-center justify-between">
            <p className="text-sm">
              {profile.data?.displayName ? (
                profile.data.displayName
              ) : (
                <span className="text-muted-foreground">No display name set yet.</span>
              )}
            </p>
            <Button type="button" variant="outline" size="sm" onClick={() => setEditing(true)}>
              Edit
            </Button>
          </div>
        )}
      </CardContent>
    </Card>
  )
}

function PreferencesCard({ preferences }: { preferences: ReturnType<typeof usePreferences> }) {
  const updatePreferences = useUpdatePreferences()

  function toggle(field: "emailOptIn" | "smsOptIn") {
    if (!preferences.data) return
    updatePreferences.mutate({ ...preferences.data, [field]: !preferences.data[field] })
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-lg">Notifications</CardTitle>
        <CardDescription>How we reach you about bookings and events.</CardDescription>
      </CardHeader>
      <CardContent>
        {preferences.isLoading ? (
          <p className="text-sm text-muted-foreground">Loading preferences…</p>
        ) : preferences.isError ? (
          <p role="alert" className="text-sm font-medium text-destructive">
            Could not load preferences. {preferences.error.message}
          </p>
        ) : (
          <div className="flex flex-col gap-3">
            <PreferenceToggle
              label="Email updates"
              checked={preferences.data?.emailOptIn ?? false}
              disabled={updatePreferences.isPending}
              onToggle={() => toggle("emailOptIn")}
            />
            <PreferenceToggle
              label="SMS updates"
              checked={preferences.data?.smsOptIn ?? false}
              disabled={updatePreferences.isPending}
              onToggle={() => toggle("smsOptIn")}
            />
          </div>
        )}
      </CardContent>
    </Card>
  )
}

function PreferenceToggle({
  label,
  checked,
  disabled,
  onToggle,
}: {
  label: string
  checked: boolean
  disabled: boolean
  onToggle: () => void
}) {
  return (
    <div className="flex items-center justify-between">
      <Label htmlFor={`toggle-${label}`} className="text-sm font-normal">
        {label}
      </Label>
      <button
        id={`toggle-${label}`}
        type="button"
        role="switch"
        aria-checked={checked}
        disabled={disabled}
        onClick={onToggle}
        className={`relative h-6 w-11 shrink-0 rounded-full border transition-colors focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50 disabled:cursor-not-allowed disabled:opacity-50 ${
          checked ? "bg-primary border-primary" : "bg-input border-input"
        }`}
      >
        <span
          className={`block size-4 rounded-full bg-background shadow transition-transform ${
            checked ? "translate-x-[22px]" : "translate-x-[3px]"
          }`}
        />
      </button>
    </div>
  )
}
