import { Loader2, Ticket } from "lucide-react"
import { Link, useParams } from "react-router-dom"

import { Button } from "@/components/ui/button"
import { useAuthStore } from "@/stores/auth"
import { useBrowseEventById, useEventSessions } from "./api"
import type { BrowseSession } from "./types"

/**
 * The buyer-facing landing point a browse card links to. Public, same as
 * BrowsePage — works signed in or out. Picking a session sends the buyer
 * into the existing protected seat selection flow; ProtectedRoute bounces
 * a signed-out click to /login first, same as every other protected link
 * in this app.
 */
export function EventDetailPage() {
  const { eventId } = useParams<{ eventId: string }>()
  const accessToken = useAuthStore((state) => state.accessToken)

  const event = useBrowseEventById(eventId ?? "")
  const sessions = useEventSessions(eventId ?? "")

  return (
    <main className="marquee-backdrop min-h-dvh px-4 pb-16">
      <div className="mx-auto flex w-full max-w-3xl flex-col gap-8">
        <header className="flex items-center justify-between gap-4 pt-8">
          <Link to="/" className="flex items-center gap-2 text-lg font-semibold tracking-tight">
            <Ticket aria-hidden="true" className="size-5" />
            TicketMaster
          </Link>
          {!accessToken ? (
            <Link to="/login" className="text-sm font-medium text-muted-foreground hover:text-foreground">
              Sign in
            </Link>
          ) : null}
        </header>

        {event.isLoading ? (
          <div className="glass-panel flex items-center justify-center gap-2 rounded-xl p-12 text-muted-foreground">
            <Loader2 aria-hidden="true" className="size-4 animate-spin" />
            Loading event…
          </div>
        ) : null}

        {event.isError ? (
          <div className="glass-panel rounded-xl p-12 text-center text-muted-foreground">
            Couldn't load this event. It may not exist or may not be on sale.
          </div>
        ) : null}

        {event.isSuccess ? (
          <section className="flex flex-col gap-3 pt-4">
            <h1 className="text-4xl font-semibold tracking-tight sm:text-5xl">{event.data.title}</h1>
            <p className="text-lg text-muted-foreground">{event.data.region}</p>
          </section>
        ) : null}

        <section className="flex flex-col gap-4">
          <h2 className="text-sm font-medium tracking-wide text-muted-foreground uppercase">
            Sessions
          </h2>

          {sessions.isLoading ? (
            <div className="glass-panel flex items-center justify-center gap-2 rounded-xl p-8 text-muted-foreground">
              <Loader2 aria-hidden="true" className="size-4 animate-spin" />
              Loading sessions…
            </div>
          ) : null}

          {sessions.isError ? (
            <div className="glass-panel rounded-xl p-8 text-center text-muted-foreground">
              Couldn't load sessions right now.
            </div>
          ) : null}

          {sessions.isSuccess && sessions.data.length === 0 ? (
            <div className="glass-panel rounded-xl p-8 text-center text-muted-foreground">
              No sessions scheduled yet.
            </div>
          ) : null}

          {sessions.isSuccess && sessions.data.length > 0 ? (
            <div className="flex flex-col gap-3">
              {sessions.data.map((session) => (
                <SessionRow key={session.id} eventId={eventId ?? ""} session={session} />
              ))}
            </div>
          ) : null}
        </section>
      </div>
    </main>
  )
}

function SessionRow({ eventId, session }: { eventId: string; session: BrowseSession }) {
  const startsAt = new Date(session.startsAt)
  const canSelectSeats = session.status === "SCHEDULED" || session.status === "ON_SALE"

  return (
    <article className="glass-panel flex items-center justify-between gap-4 rounded-xl p-5">
      <div className="flex flex-col gap-1">
        <span className="font-medium">
          {startsAt.toLocaleDateString(undefined, { weekday: "long", month: "long", day: "numeric" })}
        </span>
        <span className="text-sm text-muted-foreground">
          {startsAt.toLocaleTimeString(undefined, { hour: "numeric", minute: "2-digit" })}
        </span>
      </div>
      {canSelectSeats ? (
        <Button asChild size="sm">
          <Link to={`/events/${eventId}/sessions/${session.id}/seats`}>Select seats</Link>
        </Button>
      ) : (
        <span className="text-sm text-muted-foreground">{session.status}</span>
      )}
    </article>
  )
}
