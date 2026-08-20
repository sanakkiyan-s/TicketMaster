import * as React from "react"
import { Loader2, Mic2, Plus } from "lucide-react"

import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Textarea } from "@/components/ui/textarea"
import { useArtists, useCreateArtist } from "./api"
import { ErrorBanner, FormField } from "./components/FormField"
import { OrganizerLayout } from "./components/OrganizerLayout"

/**
 * Standalone list+create page, not a session-linking picker: SessionController
 * and its Create/UpdateSessionRequest carry no artist reference at all (read
 * from backend/event-service/src/main/java/.../session/*.java) — there is no
 * session-side field to wire a combobox into yet, so building that UI would
 * be inventing a backend capability that doesn't exist.
 */
export function ArtistsPage() {
  const [search, setSearch] = React.useState("")
  const artists = useArtists(search)
  const createArtist = useCreateArtist()

  const [name, setName] = React.useState("")
  const [bio, setBio] = React.useState("")
  const [touched, setTouched] = React.useState(false)
  const nameError = touched && !name.trim() ? "Name is required" : createArtist.error?.fieldErrors.name

  function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    setTouched(true)
    if (!name.trim()) return

    createArtist.mutate(
      { name: name.trim(), bio: bio.trim() || undefined },
      {
        onSuccess: () => {
          setName("")
          setBio("")
          setTouched(false)
        },
      },
    )
  }

  return (
    <OrganizerLayout title="Artists" description="Shared catalog — not organizer-owned.">
      <Card>
        <CardHeader>
          <CardTitle className="text-lg">Search</CardTitle>
        </CardHeader>
        <CardContent>
          <Input
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search by name…"
            aria-label="Search artists by name"
          />

          {artists.isLoading ? (
            <p className="flex items-center gap-2 text-sm text-muted-foreground">
              <Loader2 aria-hidden="true" className="size-4 animate-spin" />
              Loading artists…
            </p>
          ) : artists.isError ? (
            <ErrorBanner message={`Could not load artists. ${artists.error.message}`} />
          ) : artists.data && artists.data.length > 0 ? (
            <ul className="flex flex-col divide-y divide-border">
              {artists.data.map((artist) => (
                <li key={artist.id} className="flex flex-col gap-0.5 py-3 first:pt-0 last:pb-0">
                  <span className="font-medium">{artist.name}</span>
                  {artist.bio ? <span className="text-sm text-muted-foreground">{artist.bio}</span> : null}
                </li>
              ))}
            </ul>
          ) : (
            <div className="flex flex-col items-center gap-2 py-6 text-center">
              <Mic2 aria-hidden="true" className="size-6 text-muted-foreground" />
              <p className="text-sm text-muted-foreground">
                {search.trim() ? "No artists match that search." : "No artists yet."}
              </p>
            </div>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle className="text-lg">Add artist</CardTitle>
          <CardDescription>Matches CreateArtistRequest's fields.</CardDescription>
        </CardHeader>
        <form onSubmit={handleSubmit} noValidate>
          <CardContent>
            {createArtist.isError && Object.keys(createArtist.error.fieldErrors).length === 0 ? (
              <ErrorBanner message={createArtist.error.message} />
            ) : null}

            <FormField id="artist-name" error={nameError}>
              <Label htmlFor="artist-name">Name</Label>
              <Input
                id="artist-name"
                value={name}
                onChange={(e) => setName(e.target.value)}
                maxLength={255}
                aria-invalid={!!nameError}
              />
            </FormField>

            <FormField id="artist-bio" error={createArtist.error?.fieldErrors.bio}>
              <Label htmlFor="artist-bio">Bio (optional)</Label>
              <Textarea id="artist-bio" value={bio} onChange={(e) => setBio(e.target.value)} maxLength={4096} />
            </FormField>

            <Button type="submit" size="sm" disabled={createArtist.isPending} className="self-start">
              {createArtist.isPending ? (
                <Loader2 aria-hidden="true" className="animate-spin" />
              ) : (
                <>
                  <Plus aria-hidden="true" />
                  Add artist
                </>
              )}
            </Button>
          </CardContent>
        </form>
      </Card>
    </OrganizerLayout>
  )
}
