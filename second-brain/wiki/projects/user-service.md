---
title: user-service
type: project
sources: []
related: [[system-overview]], [[auth-service]], [[ADR-037-service-internal-architecture]]
created: 2026-08-05
last-updated: 2026-08-19
---

## Purpose

User profile, saved payment methods (tokenized references only, never raw
card data), preferences, ticket-purchase history view.

## Current Implementation

Started 2026-08-19, second service after auth-service to have real code.
Verified against `backend/user-service/src/`:

- `UserServiceApplication` — port 8082 (8080 gateway, 8081 auth-service).
  Package-by-feature per [[ADR-037-service-internal-architecture]],
  mirroring auth-service's layout: `profile/`, `preferences/`,
  `paymentmethods/` feature packages, `shared/` and `config/` cross-cutting.
- `shared/CurrentUserResolver` — resolves the caller's id from the `sub`
  claim of the bearer token on the incoming request. Deliberately does
  **not** re-verify the JWT signature: per
  [[ADR-009-service-to-service-auth]], api-gateway already performs local
  signature verification at the edge before proxying, so a second full
  verification stack here (JWKS fetch, rotation handling) would duplicate
  that responsibility rather than trust it. Base64url-decodes the payload
  segment only, using the same `"user:" + userId` subject format
  auth-service's `AccessTokenIssuer` writes.
- `profile/` — `GET`/`PUT /api/v1/users/me`. `UserProfile`
  (`userId`/`displayName`/`phoneNumber`/`avatarUrl`) auto-creates an empty
  row on first access rather than 404ing — a profile exists the moment an
  account does, even blank.
- `preferences/` — `GET`/`PUT /api/v1/users/me/preferences`.
  `emailOptIn` (default true), `smsOptIn` (default false).
- `paymentmethods/` — `GET`/`POST /api/v1/users/me/payment-methods`,
  `DELETE .../{id}`. `SavedPaymentMethod` stores only
  `provider`/`providerToken` (opaque) plus display metadata
  (`brand`/`last4`) — never raw card data, per this page's own Purpose.
  `providerToken` is never echoed back in a response. Lookups scoped by
  `findByIdAndUserId`, so one user's token cannot read or delete another
  user's saved method — tested explicitly (`PaymentMethodTest`), and the
  failure mode is 404, not 403, so a caller probing another user's ids
  cannot even confirm one exists.
- `db/migration/V1__baseline.sql` — three tables, no foreign keys into
  auth-service's `users` table (services own separate databases per
  [[ADR-001-microservices-vs-modular-monolith]]; a user id here is just a
  `UUID` column value).
- `config/OpenApiConfig` + `openapi/user-service.json` — same
  generated-spec pattern as auth-service, per
  [[ADR-034-rest-edge-versioning-openapi]].
- Tests: `ProfileTest`, `PreferencesTest`, `PaymentMethodTest` (Testcontainers
  Postgres, MockMvc, mirroring `LoginTest`'s pattern), `TestTokens` (mints
  an unsigned test JWT — consistent with the "never verify, just decode"
  design, no parallel real-auth mechanism needed for tests).

**Verification status**: `./gradlew :backend:user-service:test` green,
13 tests, 0 failures (verified 2026-08-19).

## Target Design

- Spring Boot, Spring Data JPA, PostgreSQL.
- Owns: profile fields, preferences, saved payment method tokens
  (references to payment-service/provider tokens, not card data).
- Reads booking/ticket history by calling booking-service/ticket-service —
  does not own that data.
- Low QPS relative to auth-service; standard CRUD, no unusual concurrency
  concerns.

## Gap

- **Booking/ticket purchase-history view is entirely unbuilt** — the
  services it would call (booking-service, ticket-service) don't exist
  yet, so there is nothing to integrate against. Not stubbed; building a
  call to a nonexistent service would just be dead code.
- **No real payment-service validation of `providerToken`** —
  `paymentmethods/` persists whatever token string it's given. Once
  payment-service exists, adding a real validation call is the obvious
  next step, noted in `AddPaymentMethodRequest`'s own javadoc.
- **No admin/organizer-facing endpoints** — out of scope for this slice,
  no organizer identity has a documented need to reach into user-service
  yet.
- **No gRPC surface** — ADR-023's internal service-to-service gRPC exists
  for later; nothing currently calls into or out of user-service over
  gRPC.

## Open Questions

None currently — straightforward CRUD service, design questions will
surface once auth-service's user identity model is fixed.
