import * as React from "react"

import { cn } from "@/lib/utils"

/**
 * Glass surface.
 *
 * The `glass-panel` class lives in index.css, not here, and not as an inline
 * style: the CSP in index.html allows `style-src-attr 'unsafe-inline'` but
 * pins `style-src-elem 'self'`, so a runtime-injected <style> block would be
 * refused outright. Tailwind compiles to a static stylesheet, which is exactly
 * why it was chosen over CSS-in-JS.
 */
function Card({ className, ...props }: React.ComponentProps<"div">) {
  return (
    <div
      data-slot="card"
      className={cn("glass-panel flex flex-col gap-6 rounded-xl p-6 sm:p-8", className)}
      {...props}
    />
  )
}

function CardHeader({ className, ...props }: React.ComponentProps<"div">) {
  return <div data-slot="card-header" className={cn("flex flex-col gap-2", className)} {...props} />
}

function CardTitle({ className, ...props }: React.ComponentProps<"h1">) {
  return (
    <h1
      data-slot="card-title"
      className={cn("text-2xl font-semibold tracking-tight", className)}
      {...props}
    />
  )
}

function CardDescription({ className, ...props }: React.ComponentProps<"p">) {
  return (
    <p
      data-slot="card-description"
      className={cn("text-sm text-muted-foreground", className)}
      {...props}
    />
  )
}

function CardContent({ className, ...props }: React.ComponentProps<"div">) {
  return <div data-slot="card-content" className={cn("flex flex-col gap-5", className)} {...props} />
}

function CardFooter({ className, ...props }: React.ComponentProps<"div">) {
  return (
    <div
      data-slot="card-footer"
      className={cn("flex items-center justify-center text-sm", className)}
      {...props}
    />
  )
}

export { Card, CardHeader, CardTitle, CardDescription, CardContent, CardFooter }
