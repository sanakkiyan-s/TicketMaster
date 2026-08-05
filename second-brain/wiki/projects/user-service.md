---
title: user-service
type: project
sources: []
related: [[system-overview]], [[auth-service]]
created: 2026-08-05
last-updated: 2026-08-05
---

## Purpose

User profile, saved payment methods (tokenized references only, never raw
card data), preferences, ticket-purchase history view.

## Current Implementation

Not started. `backend/user-service` is an empty directory.

## Target Design

- Spring Boot, Spring Data JPA, PostgreSQL.
- Owns: profile fields, preferences, saved payment method tokens
  (references to payment-service/provider tokens, not card data).
- Reads booking/ticket history by calling booking-service/ticket-service —
  does not own that data.
- Low QPS relative to auth-service; standard CRUD, no unusual concurrency
  concerns.

## Gap

Everything.

## Open Questions

None currently — straightforward CRUD service, design questions will
surface once auth-service's user identity model is fixed.
