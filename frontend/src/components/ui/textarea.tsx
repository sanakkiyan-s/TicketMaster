import * as React from "react"

import { cn } from "@/lib/utils"

/** Same focus/invalid/translucency conventions as input.tsx, so a form mixing both reads as one system. */
function Textarea({ className, ...props }: React.ComponentProps<"textarea">) {
  return (
    <textarea
      data-slot="textarea"
      className={cn(
        "flex min-h-24 w-full rounded-md border border-input bg-background/60 px-3 py-2 text-base shadow-xs transition-[color,box-shadow,background-color] outline-none",
        "placeholder:text-muted-foreground/70",
        "focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/50",
        "aria-invalid:border-destructive aria-invalid:ring-destructive/20 dark:aria-invalid:ring-destructive/40",
        "disabled:cursor-not-allowed disabled:opacity-50",
        "dark:bg-input/30",
        className,
      )}
      {...props}
    />
  )
}

export { Textarea }
