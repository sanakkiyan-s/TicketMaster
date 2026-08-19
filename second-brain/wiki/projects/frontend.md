---
title: frontend
type: project
sources: []
related: [[system-overview]], [[api-gateway]], [[auth-service]], [[queue-service]]
created: 2026-08-05
last-updated: 2026-08-19
---

## Purpose

Client application: event browsing/search, seat map selection, waiting
room/queue UI, checkout, digital ticket display, account/ticket history.

## Current Implementation

Scaffold only (verified 2026-08-14 against `frontend/package.json`,
`frontend/src/`). Present: Vite 5 + React 18 + TypeScript, `react-router-dom`
6, `@tanstack/react-query` 5, `zustand` 5, `axios`, Stripe JS/React SDK,
Vitest + Testing Library.

Styling layer wired 2026-08-14:

- `tailwindcss` 4 via `@tailwindcss/vite` (plugin, not PostCSS) —
  `vite.config.ts`.
- `src/index.css` — `@import "tailwindcss"` + the shadcn token layers
  (`:root`/`.dark` raw values, `@theme inline` mapping them to Tailwind
  names). Imported by `src/main.tsx`.
- `@` → `src` path alias in **both** `tsconfig.json` (type checker) and
  `vite.config.ts` (bundler); shadcn generates `@/`-prefixed imports.
- `components.json` — shadcn CLI config (new-york style, neutral base,
  CSS variables, lucide icons).
- `src/lib/utils.ts` — the `cn()` clsx + tailwind-merge helper every
  shadcn component imports.
- `src/components/ui/button.tsx`, `src/components/ui/dialog.tsx` —
  first generated components, proving the pipeline.

`npm run typecheck` and `npm run build` both pass. Build emits
`dist/assets/index-*.css` (21.3 kB / 4.6 kB gzipped) — a static
stylesheet, which is the concrete form of the CSP argument below.

**Auth flow implemented** (predates this note; login/register landed
2026-08-1x, silent refresh/logout/testing infra added 2026-08-19 — this
page previously said "no stores, no routes, no pages yet," which was
already wrong before today's addition, not just after it):

- `stores/auth.ts` — `authStore`, in-memory only (no `localStorage`/
  `sessionStorage`/zustand `persist`), matching the Target Design's own
  stated split and this session's XSS-vs-reload tradeoff, documented in
  the file itself.
- `features/auth/` — `LoginPage`, `RegisterPage`, `AuthShell`,
  `ProtectedRoute` (UX-only redirect gate, not a security boundary — the
  API enforces regardless), `useAuthMutations.ts` (`useLogin`,
  `useRegister`, `useLogout`), `useSilentRefresh.ts` (calls
  `POST /api/v1/auth/refresh` once on app load via the httpOnly cookie;
  `user` stays `null` after a silent refresh rather than decoding JWT
  claims client-side — `RefreshResponse` carries no email field to
  populate it with, and every consumer already tolerates a null user).
- `main.tsx` — `SessionGate` wraps the router, runs silent refresh before
  any route renders so `ProtectedRoute`'s redirect decision sees
  post-refresh state.
- Login form distinguishes 429 (rate-limited) from 401 with its own
  message; 401 stays the SAME generic message for unknown-email,
  wrong-password, AND locked-account — deliberately, matching
  auth-service's `InvalidCredentialsException` anti-enumeration design
  (see [[auth-service]]). Do not add a distinct "account locked" UI
  message without first re-reading why that file treats all three as one
  response.
- `logout` calls `POST /api/v1/auth/logout` and clears local session
  state in `onSettled`, not `onSuccess` — a failed/missing logout call
  must never leave the UI stuck showing "logged in".
- Test infrastructure added from scratch 2026-08-19 (none existed
  before): `vitest.config.ts` (jsdom environment), `src/test/setup.ts`
  (jest-dom + explicit RTL `cleanup()` — without `test.globals: true`,
  RTL's auto-cleanup never registers and tests leak DOM state into each
  other). 7 tests across 3 files, all passing as of 2026-08-19.
- `lib/gateway.ts` still exists as the pre-auth scaffold entry point
  mentioned below; `lib/api.ts` is the actual HTTP client the auth
  feature uses (`apiPost`, `ApiError`/`ProblemDetail` types matching
  auth-service's RFC 9457 shape).

Everything else in this page's original scaffold description below still
holds — Tailwind/shadcn wiring, CSP posture, build/typecheck status.

## Target Design

- React, TypeScript, **Vite** (build/dev server, static bundle served via
  Cloudflare per [[ADR-019-cdn-vendor-choice]]).
- **React Router** for routing — plain SPA router, major version pinned.
  No SSR framework: the edge is already Nginx + Spring Cloud Gateway
  ([[api-gateway]], [[ADR-032-api-gateway-ha-and-probe-semantics]]);
  a JS server tier would duplicate it.
- **Zustand** for *client* state, **TanStack Query** for *server* state.
  The split is strict — server responses are never mirrored into Zustand.
  Stores are small and per-concern, not one god store:
  - `authStore` — session/token state ([[ADR-012-jwt-lifecycle]])
  - `seatSelectionStore` — in-progress seat picks + live seat status from
    SSE ([[seat-availability-live-updates]],
    [[ADR-022-sse-connection-admission-control]])
  - `queueStore` — admission token + queue position
    ([[ADR-014-anti-bot-anti-scalper]])
  - `checkoutStore` — saga step + in-flight booking `Idempotency-Key`
    ([[ADR-025-idempotency-key-policy]])

  Zustand chosen over Redux (boilerplate outsized for this state volume)
  and Context (all consumers re-render on any change — wrong fit for the
  high-frequency SSE seat-status push, the only unusual state load here).
  Selector-scoped subscriptions bound re-renders to the seats that
  actually changed, and the store is writable from outside React, which
  is what an `EventSource.onmessage` handler needs — no context plumbing
  of the SSE connection. Components subscribe with a selector
  (`useSeatStore(s => s.seats[id])`), never the whole store.
- **Tailwind CSS** for styling + **shadcn/ui** for components, decided
  2026-08-14. shadcn is not a dependency — its components are copied as
  source into `src/components/ui/` and owned/edited here; the actual
  runtime deps are Tailwind and the `@radix-ui/*` primitives each
  component wraps.

  Two constraints from this system decided it, not preference:

  1. **CSP / PCI.** `frontend/index.html` ships a `style-src 'self'
     'unsafe-inline'` policy and states that PCI-DSS v4.0 6.4.3 / 11.6.1
     apply even at [[ADR-011-pci-scope-containment]]'s SAQ A scope.
     Runtime CSS-in-JS (styled-components, Emotion, and therefore MUI)
     injects `<style>` at runtime and so *permanently requires*
     `'unsafe-inline'` or nonce plumbing. Tailwind compiles to a static
     `.css` file, leaving `'unsafe-inline'` droppable from `style-src`
     later. This is the decisive argument.
  2. **Bundle budget is pre-spent.** [[ADR-016-multi-region-cdn]]'s seat
     page already loads a multi-MB layout SVG. A batteries-included
     library (MUI ≈ 90KB gz + an Emotion runtime) buys visual defaults
     that would be overridden anyway and fails point 1.

  Radix (via shadcn) is carried specifically for focus-trap / `aria-modal`
  / escape / scroll-lock correctness on the checkout dialog and queue
  modal — the a11y work that is easy to get subtly wrong and that no test
  in [[ADR-008-testing-strategy]]'s pyramid would catch.

  **The seat map is excluded from all of this.** It is a hand-rolled
  `<svg>` fed by ADR-016's versioned geometry asset, styled by a plain
  `seatmap.css` keyed on `[data-status="held|sold|available"]` — not
  Tailwind utility classes. Seat state changes arrive over SSE at high
  frequency; driving thousands of seats by swapping class strings is the
  wrong mechanism when a single attribute flip does it.
- All API calls go through `api-gateway`, never directly to individual
  backend services.
- Queue position updates via WebSocket/SSE connection to queue-service
  (through the gateway).
- Interactive seat map rendering driven by venue-service's layout data.

## Gap

Auth flow (login/register/refresh/logout) is built — see above. Still
nothing beyond that: no event browsing/search, no seat map, no queue UI,
no checkout, no ticket display, no account/history pages. `seatStore`,
`queueStore`, `checkoutStore` from Target Design don't exist yet — only
`authStore` does. No admin-facing UI either (banning users, triggering
key rotation are ADMIN-only backend operations with no frontend surface
by design, not an oversight — see [[auth-service]]'s rotation/revocation
work).

## Open Questions

- **Applied 2026-08-14, unverified.** `frontend/index.html` now carries
  `style-src-elem 'self'; style-src-attr 'unsafe-inline';` alongside the
  original `style-src 'self' 'unsafe-inline'` (kept as the Safari
  fallback — the split directives are Chromium/Firefox only). Applied
  early *because* nothing is integrated yet: the first Stripe or Radix
  violation then surfaces as a dev console error, instead of being found
  while retrofitting a policy onto working code. Nothing has exercised
  it — no dialog is rendered anywhere, Stripe is not wired up. If
  Stripe.js injects a `<style>` block for iframe sizing,
  `style-src-elem 'self'` breaks checkout and must be relaxed. Revert is
  two lines. Note the meta tag is only the dev floor; production must
  send the same policy as a real response header at the edge.

  Why the split rather than dropping `'unsafe-inline'` outright: The directive covers two distinct things:
  inline `<style>` *blocks* (which carry selectors, and so can do the
  CSS-exfiltration and fake-overlay attacks the policy exists to stop)
  and `style="..."` *attributes* (no selectors — they style only the
  element they sit on, so an attacker able to place one already has DOM
  injection). Tailwind removes the dependency on the first; Radix still
  needs the second, since Floating UI writes runtime-computed
  `transform: translate3d(...)` coordinates and Dialog's scroll lock sets
  `overflow: hidden` on `<body>`. Realistic end state:
  `style-src-elem 'self'; style-src-attr 'unsafe-inline';` with plain
  `style-src 'self' 'unsafe-inline'` retained as the Safari fallback
  (the split directives are Chromium/Firefox only). Still to verify:
  whether Stripe.js needs `<style>`-block injection for its iframe
  sizing, which would block even `style-src-elem 'self'`
  ([[ADR-011-pci-scope-containment]]).
- Interactive seat-map data format is still owned by [[venue-service]]'s
  open question (seat coordinates for frontend rendering) — the renderer
  can't be built until that lands.
