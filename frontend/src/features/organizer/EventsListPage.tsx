import { Link } from "react-router-dom"
import { CalendarPlus, Loader2 } from "lucide-react"

import { Button } from "@/components/ui/button"
import { Card, CardContent } from "@/components/ui/card"
import { useOrganizerEvents } from "./api"
import { NewEventButton, OrganizerLayout } from "./components/OrganizerLayout"
import { ErrorBanner } from "./components/FormField"
import type { EventStatus } from "./types"

const STATUS_STYLES: Record<EventStatus, string> = {
  DRAFT: "bg-muted text-muted-foreground",
  PUBLISHED: "bg-primary/15 text-primary",
  CANCELLED: "bg-destructive/15 text-destructive",
}

export function EventsListPage() {
  const events = useOrganizerEvents()

  return (
    <OrganizerLayout
      title="Your events"
      description="Events you organize, from event-service."
      actions={<NewEventButton />}
    >
      <Card>
        <CardContent>
          {events.isLoading ? (
            <p className="flex items-center gap-2 text-sm text-muted-foreground">
              <Loader2 aria-hidden="true" className="size-4 animate-spin" />
              Loading events…
            </p>
          ) : events.isError ? (
            <ErrorBanner message={`Could not load your events. ${events.error.message}`} />
          ) : events.data && events.data.length > 0 ? (
            <ul className="flex flex-col divide-y divide-border">
              {events.data.map((event) => (
                <li key={event.id}>
                  <Link
                    to={`/organizer/events/${event.id}`}
                    className="flex items-center justify-between gap-4 rounded-md py-3 outline-none first:pt-0 last:pb-0 focus-visible:ring-[3px] focus-visible:ring-ring/50"
                  >
                    <div className="flex flex-col gap-1">
                      <span className="font-medium">{event.title}</span>
                      <span className="text-sm text-muted-foreground">{event.region}</span>
                    </div>
                    <span
                      className={`shrink-0 rounded-full px-2.5 py-0.5 text-xs font-medium ${STATUS_STYLES[event.status]}`}
                    >
                      {event.status}
                    </span>
                  </Link>
                </li>
              ))}
            </ul>
          ) : (
            <div className="flex flex-col items-center gap-3 py-8 text-center">
              <CalendarPlus aria-hidden="true" className="size-8 text-muted-foreground" />
              <p className="text-sm text-muted-foreground">
                No events yet. Create your first one to get started.
              </p>
              <Button asChild size="sm">
                <Link to="/organizer/events/new">Create event</Link>
              </Button>
            </div>
          )}
        </CardContent>
      </Card>
    </OrganizerLayout>
  )
}
