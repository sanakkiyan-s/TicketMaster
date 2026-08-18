import { clsx, type ClassValue } from "clsx";
import { twMerge } from "tailwind-merge";

/**
 * Merge Tailwind classes with conflict resolution.
 *
 * Every shadcn component imports this. `clsx` handles the conditional
 * forms (`cn("p-2", isActive && "bg-accent")`); `twMerge` resolves
 * conflicts by Tailwind's own precedence rather than CSS source order —
 * `cn("px-2", "px-4")` yields `px-4`, not both.
 *
 * That is what makes the shadcn `className` prop work as an override: a
 * caller passing `className="max-w-3xl"` to a component whose base is
 * `max-w-lg` should win, and plain string concatenation would leave both
 * classes fighting.
 */
export function cn(...inputs: ClassValue[]): string {
  return twMerge(clsx(inputs));
}
