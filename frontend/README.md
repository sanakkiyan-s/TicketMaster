# frontend

React + TypeScript + Vite client. See
[`second-brain/wiki/projects/frontend.md`](../second-brain/wiki/projects/frontend.md)
for the target design this scaffold implements.

## Setup

```bash
npm install
cp .env.example .env.local
npm run dev          # http://localhost:5173, /api proxied to the gateway
```

## Rules this codebase must not break

These are not style preferences — each one comes from an accepted ADR,
and breaking it breaks a guarantee the backend depends on.

**1. Only api-gateway. Ever.**
No component may `fetch` a backend service directly. Everything goes
through `src/lib/gateway.ts`, which pins the base URL to `/api/v1`
(ADR-034) and is proxied to a single gateway origin in `vite.config.ts`.

**2. Card data never touches this app.**
Stripe's hosted iframe collects it (ADR-011). Using Stripe.js with our
own `<input>` fields would move the system from PCI SAQ A (~30 controls)
to SAQ A-EP and expose us to Magecart-class script attacks. The CSP in
`index.html` enforces the boundary; production must also send it as a
real response header at the edge.

**3. Refresh is single-flighted.**
ADR-012 treats a second use of a refresh token as a stolen-token replay
and kills the whole token family. Two tabs refreshing at once would look
exactly like that attack. `gateway.ts` collapses concurrent refreshes
into one in-flight request — do not "simplify" that away.

**4. An `Idempotency-Key` is per attempt, not per request.**
ADR-025: a retry of the same logical attempt reuses the key; a new
attempt gets a new one. `request()` requires `idempotencyKey` on every
write *at the type level* — call `newIdempotencyKey()` once per user
intent (one Buy click) and hold it across retries. It is deliberately not
defaulted: a per-request key would make writes look deduplicated while
double-booking on the first retry.

**5. Seat geometry and seat occupancy are separate.**
ADR-016 caches the seat map's geometry on the CDN and keeps occupancy
live. Geometry comes over REST; occupancy comes over the SSE stream in
`subscribeToSeatStatus`. Merging them would either make the CDN serve
stale availability or make the map uncacheable.

**6. SSE refusal is a real state, not an error to swallow.**
ADR-022 caps concurrent connections per gateway instance and fails
closed. `onUnavailable` fires when that happens; the UI must degrade
visibly (fall back to reading the seat map) rather than silently showing
a frozen map.

## Choices made here that no ADR had settled

`frontend.md` fixed React, TypeScript and Vite, and left the data layer
open ("to be picked once API contracts exist" — they now do, via
ADR-034). This scaffold picks **TanStack Query** for server state and
**React Router** for routing. Both are conventional and replaceable;
neither is recorded as an architectural decision yet. Deliberately no
global client-state library — nothing in the design needs one yet.

**HTTP transport: axios, behind `request()`.**
Chosen while there were zero call sites, so the cost was one file; the
same swap after the service modules exist would touch all of them. The
boundary is the point, not the library — nothing outside `gateway.ts`
imports axios, and no axios type appears in an exported signature.
Callers bind only to `request`, `RequestOptions`, `ApiError` and
`NetworkError`, so the transport stays replaceable. `AxiosError` is
normalized into `NetworkError` at the boundary specifically to keep that
true. Two consequences worth knowing:

- **No interceptors.** The 401 replay needs a rebuilt `Authorization`
  header but the *same* `Idempotency-Key`. Via interceptors the replay
  re-enters the request interceptor with the prior config, so key
  preservation reduces to remembering `??=` over `=` — a silent
  double-booking with no type error and no failing test. Explicit control
  flow in `request()` makes that unrepresentable. Interceptors remain
  fine for logging or telemetry.
- **SSE is unaffected.** `subscribeToSeatStatus` uses the native
  `EventSource`; axios has no equivalent, so that half of the file is
  outside the transport decision.
