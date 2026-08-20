import * as React from "react"
import { useNavigate, useParams } from "react-router-dom"
import { Loader2, Plus } from "lucide-react"

import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Textarea } from "@/components/ui/textarea"
import { useCancelEvent, useCancelSession, useCreateSession, useOrganizerEvent, useUpdateEvent } from "./api"
import { ErrorBanner, FormField } from "./components/FormField"
import { OrganizerLayout } from "./components/OrganizerLayout"
import type { OrganizerSession } from "./types"

export function EventDetailPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()

  if (!id) {
    return <NotFoundState onBack={() => navigate("/organizer/events")} />
  }

  return <EventDetailContent id={id} />
}

function EventDetailContent({ id }: { id: string }) {
  const navigate = useNavigate()
  const event = useOrganizerEvent(id)

  if (event.isLoading) {
    return (
      <OrganizerLayout title="Loading…">
        <p className="flex items-center gap-2 text-sm text-muted-foreground">
          <Loader2 aria-hidden="true" className="size-4 animate-spin" />
          Loading event…
        </p>
      </OrganizerLayout>
    )
  }

  // EventController answers "doesn't exist" and "not yours" identically
  // with 404 (ADR-030, IDOR-oracle avoidance) — so every non-404 failure
  // gets a distinct banner, and 404 gets the dedicated not-found state.
  if (event.isError) {
    if (event.error.status === 404) {
      return <NotFoundState onBack={() => navigate("/organizer/events")} />
    }
    return (
      <OrganizerLayout title="Event">
        <Card>
          <CardContent>
            <ErrorBanner message={`Could not load this event. ${event.error.message}`} />
          </CardContent>
        </Card>
      </OrganizerLayout>
    )
  }

  const data = event.data!

  return (
    <OrganizerLayout title={data.title} description={`${data.region} · ${data.status}`}>
      <EventEditCard event={data} />
      <CancelEventCard eventId={id} status={data.status} />
      <SessionsCard eventId={id} eventStatus={data.status} />
    </OrganizerLayout>
  )
}

function NotFoundState({ onBack }: { onBack: () => void }) {
  return (
    <OrganizerLayout title="Event not found">
      <Card>
        <CardContent>
          <p className="text-sm text-muted-foreground">
            This event doesn't exist, or isn't one of yours.
          </p>
          <Button variant="outline" size="sm" onClick={onBack}>
            Back to events
          </Button>
        </CardContent>
      </Card>
    </OrganizerLayout>
  )
}

function EventEditCard({
  event,
}: {
  event: { id: string; venueId: string; title: string; description: string | null; category: string | null }
}) {
  const updateEvent = useUpdateEvent(event.id)
  const [editing, setEditing] = React.useState(false)
  const [title, setTitle] = React.useState(event.title)
  const [description, setDescription] = React.useState(event.description ?? "")
  const [category, setCategory] = React.useState(event.category ?? "")

  function startEditing() {
    setTitle(event.title)
    setDescription(event.description ?? "")
    setCategory(event.category ?? "")
    setEditing(true)
  }

  function handleSubmit(formEvent: React.FormEvent) {
    formEvent.preventDefault()
    if (!title.trim()) return
    updateEvent.mutate(
      {
        // venueId isn't user-editable in this form (no picker exists yet —
        // see the create form's note), so the update round-trips the
        // value the event already has rather than silently blanking it.
        venueId: event.venueId,
        title: title.trim(),
        description: description.trim() || undefined,
        category: category.trim() || undefined,
      },
      { onSuccess: () => setEditing(false) },
    )
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-lg">Details</CardTitle>
        <CardDescription>Title, description, and category — region is fixed at creation.</CardDescription>
      </CardHeader>
      <CardContent>
        {editing ? (
          <form onSubmit={handleSubmit} className="flex flex-col gap-4">
            {updateEvent.isError && Object.keys(updateEvent.error.fieldErrors).length === 0 ? (
              <ErrorBanner message={updateEvent.error.message} />
            ) : null}

            <FormField id="edit-title" error={updateEvent.error?.fieldErrors.title}>
              <Label htmlFor="edit-title">Title</Label>
              <Input id="edit-title" value={title} onChange={(e) => setTitle(e.target.value)} maxLength={255} />
            </FormField>

            <FormField id="edit-category" error={updateEvent.error?.fieldErrors.category}>
              <Label htmlFor="edit-category">Category</Label>
              <Input id="edit-category" value={category} onChange={(e) => setCategory(e.target.value)} maxLength={64} />
            </FormField>

            <FormField id="edit-description" error={updateEvent.error?.fieldErrors.description}>
              <Label htmlFor="edit-description">Description</Label>
              <Textarea
                id="edit-description"
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                maxLength={4096}
              />
            </FormField>

            <div className="flex gap-2">
              <Button type="submit" size="sm" disabled={updateEvent.isPending}>
                {updateEvent.isPending ? <Loader2 aria-hidden="true" className="animate-spin" /> : "Save"}
              </Button>
              <Button type="button" variant="ghost" size="sm" onClick={() => setEditing(false)} disabled={updateEvent.isPending}>
                Cancel
              </Button>
            </div>
          </form>
        ) : (
          <div className="flex flex-col gap-3">
            <div>
              <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">Category</p>
              <p className="text-sm">{event.category || <span className="text-muted-foreground">None</span>}</p>
            </div>
            <div>
              <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">Description</p>
              <p className="text-sm whitespace-pre-wrap">
                {event.description || <span className="text-muted-foreground">None</span>}
              </p>
            </div>
            <Button variant="outline" size="sm" className="self-start" onClick={startEditing}>
              Edit
            </Button>
          </div>
        )}
      </CardContent>
    </Card>
  )
}

function CancelEventCard({ eventId, status }: { eventId: string; status: string }) {
  const cancelEvent = useCancelEvent(eventId)
  const [confirmOpen, setConfirmOpen] = React.useState(false)
  const alreadyCancelled = status === "CANCELLED"

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-lg">Cancel event</CardTitle>
        <CardDescription>
          Soft-cancel — sets status to CANCELLED. It looks irreversible from here, so confirm first.
        </CardDescription>
      </CardHeader>
      <CardContent>
        {cancelEvent.isError ? <ErrorBanner message={cancelEvent.error.message} /> : null}
        <Dialog open={confirmOpen} onOpenChange={setConfirmOpen}>
          <Button
            type="button"
            variant="destructive"
            size="sm"
            className="self-start"
            disabled={alreadyCancelled || cancelEvent.isPending}
            onClick={() => setConfirmOpen(true)}
          >
            {alreadyCancelled ? "Already cancelled" : "Cancel event"}
          </Button>
          <DialogContent>
            <DialogHeader>
              <DialogTitle>Cancel this event?</DialogTitle>
              <DialogDescription>
                This sets the event's status to CANCELLED. Attendees and sessions are affected. This action
                cannot be undone from this screen.
              </DialogDescription>
            </DialogHeader>
            <DialogFooter>
              <DialogClose asChild>
                <Button variant="outline">Keep event</Button>
              </DialogClose>
              <Button
                variant="destructive"
                disabled={cancelEvent.isPending}
                onClick={() => cancelEvent.mutate(undefined, { onSuccess: () => setConfirmOpen(false) })}
              >
                {cancelEvent.isPending ? <Loader2 aria-hidden="true" className="animate-spin" /> : "Yes, cancel event"}
              </Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>
      </CardContent>
    </Card>
  )
}

function SessionsCard({ eventId, eventStatus }: { eventId: string; eventStatus: string }) {
  const createSession = useCreateSession(eventId)
  const cancelSession = useCancelSession(eventId)

  // See api.ts's useCreateSession javadoc: SessionController has no GET,
  // so this list can only ever reflect sessions created/cancelled in the
  // current page visit — never a page-load fetch.
  const [sessions, setSessions] = React.useState<OrganizerSession[]>([])
  const [startsAt, setStartsAt] = React.useState("")
  const [endsAt, setEndsAt] = React.useState("")
  const [onSaleAt, setOnSaleAt] = React.useState("")
  const [touched, setTouched] = React.useState(false)

  const startsAtError = touched && !startsAt ? "Start time is required" : undefined

  function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    setTouched(true)
    if (!startsAt) return

    createSession.mutate(
      {
        startsAt: new Date(startsAt).toISOString(),
        endsAt: endsAt ? new Date(endsAt).toISOString() : undefined,
        onSaleAt: onSaleAt ? new Date(onSaleAt).toISOString() : undefined,
      },
      {
        onSuccess: (created) => {
          setSessions((prev) => [...prev, created])
          setStartsAt("")
          setEndsAt("")
          setOnSaleAt("")
          setTouched(false)
        },
      },
    )
  }

  function handleCancelSession(sessionId: string) {
    cancelSession.mutate(sessionId, {
      onSuccess: (cancelled) => {
        setSessions((prev) => prev.map((s) => (s.id === cancelled.id ? cancelled : s)))
      },
    })
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-lg">Sessions</CardTitle>
        <CardDescription>
          Only sessions added in this browser visit are listed below — event-service has no endpoint to list
          existing sessions yet, so a reload starts this list empty again.
        </CardDescription>
      </CardHeader>
      <CardContent>
        {sessions.length > 0 ? (
          <ul className="flex flex-col divide-y divide-border">
            {sessions.map((session) => (
              <li key={session.id} className="flex items-center justify-between gap-4 py-3 first:pt-0 last:pb-0">
                <div className="flex flex-col gap-0.5 text-sm">
                  <span className="font-medium">{new Date(session.startsAt).toLocaleString()}</span>
                  <span className="text-muted-foreground">{session.status}</span>
                </div>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={session.status === "CANCELLED" || cancelSession.isPending}
                  onClick={() => handleCancelSession(session.id)}
                >
                  {session.status === "CANCELLED" ? "Cancelled" : "Cancel"}
                </Button>
              </li>
            ))}
          </ul>
        ) : (
          <p className="text-sm text-muted-foreground">No sessions added yet this visit.</p>
        )}

        <form onSubmit={handleSubmit} className="mt-4 flex flex-col gap-4 border-t border-border pt-4" noValidate>
          {createSession.isError ? <ErrorBanner message={createSession.error.message} /> : null}

          <FormField id="startsAt" error={startsAtError}>
            <Label htmlFor="startsAt">Starts at</Label>
            <Input
              id="startsAt"
              type="datetime-local"
              value={startsAt}
              onChange={(e) => setStartsAt(e.target.value)}
              aria-invalid={!!startsAtError}
            />
          </FormField>

          <FormField id="endsAt">
            <Label htmlFor="endsAt">Ends at (optional)</Label>
            <Input id="endsAt" type="datetime-local" value={endsAt} onChange={(e) => setEndsAt(e.target.value)} />
          </FormField>

          <FormField id="onSaleAt">
            <Label htmlFor="onSaleAt">On sale at (optional)</Label>
            <Input id="onSaleAt" type="datetime-local" value={onSaleAt} onChange={(e) => setOnSaleAt(e.target.value)} />
          </FormField>

          <Button type="submit" size="sm" disabled={createSession.isPending || eventStatus === "CANCELLED"} className="self-start">
            {createSession.isPending ? (
              <Loader2 aria-hidden="true" className="animate-spin" />
            ) : (
              <>
                <Plus aria-hidden="true" />
                Add session
              </>
            )}
          </Button>
        </form>
      </CardContent>
    </Card>
  )
}
