import * as React from "react"

import { cn } from "@/lib/utils"

/**
 * Text input, matching button.tsx's focus and invalid conventions so the two
 * agree visually.
 *
 * h-11 rather than shadcn's default h-9: 44px is the minimum touch target on
 * iOS, and a login form is where a mis-tap costs the most.
 *
 * aria-invalid drives the error styling rather than a prop, so a field cannot
 * look wrong to a sighted user while reading as fine to a screen reader.
 */
function Input({ className, type = "text", ...props }: React.ComponentProps<"input">) {
  return (
    <input
      type={type}
      data-slot="input"
      className={cn(
        "flex h-11 w-full min-w-0 rounded-md border border-input bg-background/60 px-3 py-2 text-base shadow-xs transition-[color,box-shadow,background-color] outline-none",
        "placeholder:text-muted-foreground/70",
        "focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/50",
        "aria-invalid:border-destructive aria-invalid:ring-destructive/20 dark:aria-invalid:ring-destructive/40",
        "disabled:cursor-not-allowed disabled:opacity-50",
        // Translucent so the glass panel behind stays visible, but never fully
        // transparent — a field you cannot locate is not a field.
        "dark:bg-input/30",
        className,
      )}
      {...props}
    />
  )
}

export { Input }
