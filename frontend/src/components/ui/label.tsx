import * as React from "react"

import { cn } from "@/lib/utils"

/**
 * A real <label>, always with htmlFor.
 *
 * Not a styled <span>, and never a placeholder standing in for a label: a
 * placeholder disappears the moment typing starts, leaving no way to check
 * what a field was for, and it is not reliably announced by screen readers.
 */
function Label({ className, ...props }: React.ComponentProps<"label">) {
  return (
    <label
      data-slot="label"
      className={cn(
        "flex items-center gap-1 text-sm leading-none font-medium select-none",
        "peer-disabled:cursor-not-allowed peer-disabled:opacity-70",
        className,
      )}
      {...props}
    />
  )
}

export { Label }
