import * as React from "react"
import { Loader2, LogOut, Search, Ticket } from "lucide-react"
import { Link } from "react-router-dom"

import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { useLogout } from "@/features/auth/useAuthMutations"
import { useAuthStore } from "@/stores/auth"
import { useBrowseEvents } from "./api"
import type { BrowseEvent } from "./types"

const STATUS_LABEL: Record<string, string> = {
  PUBLISHED: "On sale",
  CANCELLED: "Cancelled",
  DRAFT: "Draft",
}

/**
 * The public storefront front page — the one surface in this app meant to
 * look like Ticketmaster's actual homepage (event grid, not an account
 * screen). Works signed in or signed out: EventSearchController requires no
 * auth, and this page renders identically either way except for the header.
 *
 * Filters to PUBLISHED client-side. event-service fires event.created (and
 * so indexes into search-service) on every create, including DRAFT ones —
 * there is no publish-time gate before an event reaches this public index,
 * which is a real backend gap (event-service should only emit here on the
 * DRAFT->PUBLISHED transition). This filter is a UX mitigation, not a fix:
 * a DRAFT event's data is still reachable by anyone calling the API
 * directly (GET /api/v1/events/{id}), since that endpoint has no ownership
 * check by design (it's the one deliberately public read endpoint).
 */
export function BrowsePage() {
  const accessToken = useAuthStore((state) => state.accessToken)
  const user = useAuthStore((state) => state.user)
  const isOrganizer = user?.roles.some((role) => role === "ORGANIZER" || role === "ADMIN") ?? false
  const logout = useLogout(() => {})

  const [query, setQuery] = React.useState("")
  const events = useBrowseEvents(query)
  const published = (events.data ?? []).filter((event) => event.status === "PUBLISHED")

  return (
    <main className="marquee-backdrop min-h-dvh px-4 pb-16">
      <div className="mx-auto flex w-full max-w-6xl flex-col gap-10">
        <header className="flex items-center justify-between gap-4 pt-8">
          <div className="flex items-center gap-2 text-lg font-semibold tracking-tight">
            <Ticket aria-hidden="true" className="size-5" />
            TicketMaster
          </div>
          <nav className="flex items-center gap-3 text-sm">
            {accessToken ? (
              <>
                {isOrganizer ? (
                  <Link
                    to="/organizer/events"
                    className="font-medium text-muted-foreground hover:text-foreground"
                  >
                    Organizer dashboard
                  </Link>
                ) : null}
                <Link to="/account" className="font-medium text-muted-foreground hover:text-foreground">
                  My account
                </Link>
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
              </>
            ) : (
              <>
                <Link to="/login" className="font-medium text-muted-foreground hover:text-foreground">
                  Sign in
                </Link>
                <Button asChild size="sm">
                  <Link to="/register">Create account</Link>
                </Button>
              </>
            )}
          </nav>
        </header>

        <section className="flex flex-col gap-6 pt-8 pb-2">
          <h1 className="max-w-2xl text-5xl font-semibold tracking-tight sm:text-6xl">
            Live events, right now.
          </h1>
          <p className="max-w-xl text-lg text-muted-foreground">
            Every event on sale, in one place — search by name to find yours.
          </p>
          <label className="relative max-w-md">
            <span className="sr-only">Search events</span>
            <Search
              aria-hidden="true"
              className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted-foreground"
            />
            <Input
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder="Search events"
              className="pl-9"
            />
          </label>
        </section>

        {events.isLoading ? (
          <div className="glass-panel flex items-center justify-center gap-2 rounded-xl p-12 text-muted-foreground">
            <Loader2 aria-hidden="true" className="size-4 animate-spin" />
            Loading events…
          </div>
        ) : null}

        {events.isError ? (
          <div className="glass-panel rounded-xl p-12 text-center text-muted-foreground">
            Couldn't load events right now. Try again shortly.
          </div>
        ) : null}

        {events.isSuccess && published.length === 0 ? (
          <div className="glass-panel rounded-xl p-12 text-center text-muted-foreground">
            {query.trim() ? "No events match that search." : "No events on sale yet."}
          </div>
        ) : null}

        {query.trim() && published.length > 0 ? (
          <section className="flex flex-col gap-4">
            <div className="flex items-baseline justify-between">
              <h2 className="text-sm font-medium tracking-wide text-muted-foreground uppercase">
                Results for "{query.trim()}"
              </h2>
              <span className="text-sm text-muted-foreground">{published.length} on sale</span>
            </div>
            <EventGrid events={published} />
          </section>
        ) : null}

        {!query.trim() && published.length > 0 ? (
          <div className="flex flex-col gap-10">
            {groupByRegion(published).map(([region, regionEvents]) => (
              <section key={region} className="flex flex-col gap-4">
                <div className="flex items-baseline justify-between">
                  <h2 className="text-sm font-medium tracking-wide text-muted-foreground uppercase">
                    On sale near {region}
                  </h2>
                  <span className="text-sm text-muted-foreground">{regionEvents.length} events</span>
                </div>
                <EventGrid events={regionEvents} />
              </section>
            ))}
          </div>
        ) : null}
      </div>
    </main>
  )
}

/**
 * search-service's index carries only eventId/organizerId/venueId/title/
 * status/region (see types.ts) — no dates, category, or images, so "curated
 * sections" here means grouping by the one real axis available (region)
 * rather than fabricating "trending"/"new this week" buckets the backend
 * can't actually back. Sorted by event count descending so the busiest
 * region leads.
 */
function groupByRegion(events: BrowseEvent[]): Array<[string, BrowseEvent[]]> {
  const groups = new Map<string, BrowseEvent[]>()
  for (const event of events) {
    const key = event.region || "Other"
    const existing = groups.get(key)
    if (existing) {
      existing.push(event)
    } else {
      groups.set(key, [event])
    }
  }
  return Array.from(groups.entries()).sort((a, b) => b[1].length - a[1].length)
}

function EventGrid({ events }: { events: BrowseEvent[] }) {
  return (
    <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
      {events.map((event) => (
        <EventCard key={event.eventId} event={event} />
      ))}
    </div>
  )
}

function EventCard({ event }: { event: BrowseEvent }) {
  return (
    <Link
      to={`/events/${event.eventId}`}
      className="glass-panel flex flex-col gap-4 rounded-xl p-6 transition-transform hover:-translate-y-1"
    >
      <div className="flex items-start justify-between gap-3">
        <h3 className="text-xl leading-snug font-semibold tracking-tight">{event.title}</h3>
        <span className="shrink-0 rounded-full bg-primary/10 px-2.5 py-1 text-xs font-medium text-primary">
          {STATUS_LABEL[event.status] ?? event.status}
        </span>
      </div>
      <p className="text-sm text-muted-foreground">{event.region}</p>
    </Link>
  )
}
