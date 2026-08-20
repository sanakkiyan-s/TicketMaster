import * as React from "react"
import { useNavigate } from "react-router-dom"
import { Loader2 } from "lucide-react"

import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Textarea } from "@/components/ui/textarea"
import { useCreateEvent } from "./api"
import { ErrorBanner, FormField } from "./components/FormField"
import { OrganizerLayout } from "./components/OrganizerLayout"

interface FormState {
  venueId: string
  title: string
  description: string
  category: string
  region: string
}

const EMPTY_FORM: FormState = { venueId: "", title: "", description: "", category: "", region: "" }

/**
 * UUID_PATTERN mirrors what @NotNull UUID venueId means client-side:
 * CreateEventRequest.venueId deserializes as a UUID, so a malformed string
 * would fail Jackson binding before @Valid even runs and come back as a
 * generic 400 with no field-level detail. Catching the shape client-side
 * gets the organizer a specific, in-place error instead of a bare banner.
 */
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

function validate(form: FormState): Partial<Record<keyof FormState, string>> {
  const errors: Partial<Record<keyof FormState, string>> = {}
  if (!form.venueId.trim()) errors.venueId = "Venue ID is required"
  else if (!UUID_PATTERN.test(form.venueId.trim())) errors.venueId = "Must be a valid UUID"
  if (!form.title.trim()) errors.title = "Title is required"
  else if (form.title.length > 255) errors.title = "Title must be 255 characters or fewer"
  if (form.description.length > 4096) errors.description = "Description must be 4096 characters or fewer"
  if (form.category.length > 64) errors.category = "Category must be 64 characters or fewer"
  if (!form.region.trim()) errors.region = "Region is required"
  else if (form.region.length > 32) errors.region = "Region must be 32 characters or fewer"
  return errors
}

export function CreateEventPage() {
  const navigate = useNavigate()
  const createEvent = useCreateEvent()

  const [form, setForm] = React.useState<FormState>(EMPTY_FORM)
  const [touched, setTouched] = React.useState(false)

  const clientErrors = validate(form)
  const serverErrors = createEvent.error?.fieldErrors ?? {}

  function fieldError(field: keyof FormState): string | undefined {
    if (serverErrors[field]) return serverErrors[field]
    if (touched && clientErrors[field]) return clientErrors[field]
    return undefined
  }

  function update<K extends keyof FormState>(key: K, value: FormState[K]) {
    setForm((prev) => ({ ...prev, [key]: value }))
  }

  function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    setTouched(true)
    if (Object.keys(validate(form)).length > 0) return

    createEvent.mutate(
      {
        venueId: form.venueId.trim(),
        title: form.title.trim(),
        description: form.description.trim() || undefined,
        category: form.category.trim() || undefined,
        region: form.region.trim(),
      },
      { onSuccess: (created) => navigate(`/organizer/events/${created.id}`, { replace: true }) },
    )
  }

  // Only render the general banner for a failure that ISN'T already
  // explained by a per-field message below — otherwise a 400 shows the
  // same complaint twice.
  const showGeneralError =
    createEvent.isError && Object.keys(createEvent.error.fieldErrors).length === 0

  return (
    <OrganizerLayout title="Create event" description="venueId is a raw UUID for now — venue-service has no picker UI yet.">
      <Card>
        <CardHeader>
          <CardTitle>Event details</CardTitle>
          <CardDescription>Matches CreateEventRequest's fields exactly.</CardDescription>
        </CardHeader>
        <form onSubmit={handleSubmit} noValidate>
          <CardContent>
            {showGeneralError ? (
              <ErrorBanner message={createEvent.error.message} />
            ) : null}

            <FormField id="venueId" error={fieldError("venueId")}>
              <Label htmlFor="venueId">Venue ID (UUID)</Label>
              <Input
                id="venueId"
                value={form.venueId}
                onChange={(e) => update("venueId", e.target.value)}
                placeholder="00000000-0000-0000-0000-000000000000"
                aria-invalid={!!fieldError("venueId")}
                aria-describedby={fieldError("venueId") ? "venueId-error" : undefined}
              />
            </FormField>

            <FormField id="title" error={fieldError("title")}>
              <Label htmlFor="title">Title</Label>
              <Input
                id="title"
                value={form.title}
                onChange={(e) => update("title", e.target.value)}
                maxLength={255}
                aria-invalid={!!fieldError("title")}
                aria-describedby={fieldError("title") ? "title-error" : undefined}
              />
            </FormField>

            <FormField id="region" error={fieldError("region")}>
              <Label htmlFor="region">Region</Label>
              <Input
                id="region"
                value={form.region}
                onChange={(e) => update("region", e.target.value)}
                maxLength={32}
                aria-invalid={!!fieldError("region")}
                aria-describedby={fieldError("region") ? "region-error" : undefined}
              />
              <p className="text-xs text-muted-foreground">
                Fixed once the event is created — UpdateEventRequest excludes it.
              </p>
            </FormField>

            <FormField id="category" error={fieldError("category")}>
              <Label htmlFor="category">Category</Label>
              <Input
                id="category"
                value={form.category}
                onChange={(e) => update("category", e.target.value)}
                maxLength={64}
                aria-invalid={!!fieldError("category")}
                aria-describedby={fieldError("category") ? "category-error" : undefined}
              />
            </FormField>

            <FormField id="description" error={fieldError("description")}>
              <Label htmlFor="description">Description</Label>
              <Textarea
                id="description"
                value={form.description}
                onChange={(e) => update("description", e.target.value)}
                maxLength={4096}
                aria-invalid={!!fieldError("description")}
                aria-describedby={fieldError("description") ? "description-error" : undefined}
              />
            </FormField>

            <Button type="submit" disabled={createEvent.isPending} className="w-full">
              {createEvent.isPending ? (
                <>
                  <Loader2 aria-hidden="true" className="animate-spin" />
                  Creating…
                </>
              ) : (
                "Create event"
              )}
            </Button>
          </CardContent>
        </form>
      </Card>
    </OrganizerLayout>
  )
}
