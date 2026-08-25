import * as React from "react"
import { Link } from "react-router-dom"
import { CalendarRange, Mic2, Plus, Ticket } from "lucide-react"

import { Button } from "@/components/ui/button"

/**
 * Shared frame for every /organizer/* page — mirrors HomePage's
 * max-w-2xl-column-of-cards shape (this repo's one established
 * "authenticated surface" layout) rather than inventing a sidebar+topbar
 * dashboard shell that doesn't exist anywhere else in the app yet.
 *
 * `dashboard-backdrop` (index.css) is auth-backdrop's technique — fixed,
 * pointer-events:none, radial-gradient wash behind the glass panels — with
 * cooler, quieter tones. A dashboard gets visited repeatedly to get work
 * done; auth-backdrop's brighter three-color wash is right for a one-time
 * arrival moment, wrong for a page an organizer reloads all day.
 */
export function OrganizerLayout({
  title,
  description,
  actions,
  children,
}: {
  title: string
  description?: string
  actions?: React.ReactNode
  children: React.ReactNode
}) {
  return (
    <main className="dashboard-backdrop min-h-dvh px-4 py-10">
      <div className="mx-auto flex w-full max-w-2xl flex-col gap-6">
        <header className="flex items-center justify-between">
          <Link
            to="/"
            className="flex items-center gap-2 rounded-md px-2 py-1 text-lg font-semibold tracking-tight outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50"
          >
            <Ticket aria-hidden="true" className="size-5" />
            TicketMaster
          </Link>
          <nav className="flex items-center gap-1 text-sm">
            <Button asChild variant="ghost" size="sm">
              <Link to="/organizer/events">
                <CalendarRange aria-hidden="true" />
                Events
              </Link>
            </Button>
            <Button asChild variant="ghost" size="sm">
              <Link to="/organizer/artists">
                <Mic2 aria-hidden="true" />
                Artists
              </Link>
            </Button>
          </nav>
        </header>

        <div className="flex items-start justify-between gap-4">
          <div>
            <h1 className="text-2xl font-semibold tracking-tight">{title}</h1>
            {description ? (
              <p className="text-sm text-muted-foreground">{description}</p>
            ) : null}
          </div>
          {actions}
        </div>

        {children}
      </div>
    </main>
  )
}

export function NewEventButton() {
  return (
    <Button asChild size="sm">
      <Link to="/organizer/events/new">
        <Plus aria-hidden="true" />
        New event
      </Link>
    </Button>
  )
}
