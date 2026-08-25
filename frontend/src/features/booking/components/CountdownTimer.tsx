import * as React from "react"

interface CountdownTimerProps {
  /** ISO timestamp — the countdown is always client-computed from this, never a server tick (ADR-002). */
  until: string
  onExpire: () => void
  className?: string
}

/**
 * Ticks once a second purely to re-render; the actual remaining time is
 * always recomputed from `until`, never accumulated locally — so a tab
 * that was backgrounded for a while still shows the correct remainder the
 * instant it repaints, not a drifted count.
 */
export function CountdownTimer({ until, onExpire, className }: CountdownTimerProps) {
  const [, forceTick] = React.useReducer((n: number) => n + 1, 0)
  const hasExpiredRef = React.useRef(false)

  React.useEffect(() => {
    const id = window.setInterval(forceTick, 1000)
    return () => window.clearInterval(id)
  }, [])

  const remainingMs = new Date(until).getTime() - Date.now()

  React.useEffect(() => {
    if (remainingMs <= 0 && !hasExpiredRef.current) {
      hasExpiredRef.current = true
      onExpire()
    }
  }, [remainingMs, onExpire])

  const clampedMs = Math.max(0, remainingMs)
  const totalSeconds = Math.floor(clampedMs / 1000)
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  const isUrgent = clampedMs < 30_000

  return (
    <span
      className={className}
      role="timer"
      aria-live={isUrgent ? "assertive" : "off"}
      aria-label={`${minutes} minutes ${seconds} seconds remaining on your hold`}
      data-urgent={isUrgent ? "true" : undefined}
    >
      {minutes}:{seconds.toString().padStart(2, "0")}
    </span>
  )
}
