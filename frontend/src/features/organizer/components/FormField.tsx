import * as React from "react"

/**
 * Organizer-feature-local twin of AuthShell's `Field` — same
 * aria-describedby/aria-invalid wiring contract, kept local rather than
 * imported cross-feature so this feature doesn't reach into `features/auth`
 * for a layout primitive that happens to look the same.
 */
export function FormField({
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

/**
 * General-failure banner for the case an ApiError carries no per-field
 * `errors` map (network failure, 500, a 404 on the parent resource) — the
 * form still needs to say something happened.
 */
export function ErrorBanner({ message }: { message: string }) {
  return (
    <p
      role="alert"
      className="rounded-md border border-destructive/40 bg-destructive/10 px-3 py-2 text-sm font-medium text-destructive"
    >
      {message}
    </p>
  )
}
