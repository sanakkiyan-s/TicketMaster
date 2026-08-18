import * as React from "react"
import { Link } from "react-router-dom"
import { Ticket } from "lucide-react"

import { Card, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"

/**
 * Shared frame for the auth pages.
 *
 * `min-h-dvh`, not `min-h-screen`: on mobile browsers 100vh includes the
 * collapsing URL bar, so a vh-sized form gets its submit button pushed under
 * the chrome. dvh tracks the actually-visible viewport.
 */
export function AuthShell({
  title,
  description,
  children,
  footer,
}: {
  title: string
  description: string
  children: React.ReactNode
  footer: React.ReactNode
}) {
  return (
    <div className="auth-backdrop flex min-h-dvh flex-col items-center justify-center gap-6 px-4 py-10">
      <Link
        to="/"
        className="flex items-center gap-2 rounded-md px-2 py-1 text-lg font-semibold tracking-tight outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50"
      >
        {/* aria-hidden: the adjacent text already names this, and a screen
            reader announcing "ticket icon TicketMaster" is noise. */}
        <Ticket aria-hidden="true" className="size-5" />
        TicketMaster
      </Link>

      <Card className="w-full max-w-md">
        <CardHeader>
          <CardTitle>{title}</CardTitle>
          <CardDescription>{description}</CardDescription>
        </CardHeader>

        {children}

        <div className="text-center text-sm text-muted-foreground">{footer}</div>
      </Card>
    </div>
  )
}

/**
 * One field: label, control, and its error, wired together.
 *
 * The wiring is the reason this exists rather than being inlined per field.
 * `aria-describedby` must point at the error node's id and `aria-invalid` must
 * be set on the input itself — done by hand at each call site, one of the two
 * gets forgotten, and the field then looks wrong while announcing as fine.
 */
export function Field({
  id,
  error,
  children,
}: {
  id: string
  error?: string
  children: React.ReactNode
}) {
  return (
    <div className="flex flex-col gap-2">
      {children}
      {error ? (
        <p id={`${id}-error`} className="text-sm font-medium text-destructive">
          {error}
        </p>
      ) : null}
    </div>
  )
}
