---
title: ADR-037 Internal architecture of a service - package by feature
type: decision
sources: []
related: [[auth-service]], [[ADR-034-rest-edge-versioning-openapi]], [[ADR-030-organizer-admin-authorization]], [[implementation-roadmap]]
created: 2026-08-14
last-updated: 2026-08-14
---

Status: Accepted

# Context

Thirty-six ADRs cover how services relate to each other - locking, sagas,
sharding, tokens, events - and none cover how a single service is
organised inside. auth-service was the first to have real code, and it
was written with the Spring default (`api/`, `service/`, `domain/`)
without a recorded reason. With 14 services still to build, an
unrecorded default becomes 15 divergent layouts.

# Requirements / Constraints

- The layout must be the same for all 15 services. Consistency is worth
  more here than the marginal fit of any one style to any one service.
- Boundaries between features should be enforced by the compiler where
  possible, not merely documented - the vault has repeatedly chosen
  designs that make a mistake unrepresentable over designs that rely on
  discipline (ADR-002's unique-constraint backstop, ADR-025's
  colocated body hash).
- It must not require a mapping layer that buys nothing for the CRUD-
  shaped services (auth, user, event, venue).
- Error responses must have one shape per service, because ADR-034
  publishes them as the OpenAPI contract.

# Options Considered

## A - Package by layer (the Spring tutorial default)

```
controller/  RegistrationController, LoginController
service/     RegistrationService, LoginService
repository/  UserRepository
dto/         RegisterRequest, LoginRequest
exception/   EmailAlreadyRegisteredException
```

- Pro: instantly familiar; anyone who has read a Spring guide can
  navigate it.
- Pro: trivial to place a new file - its type decides the folder.
- Con: **every class must be public.** A controller in `controller/`
  cannot reach a service in `service/` otherwise. So any class anywhere
  in the service can call any service class, and the compiler cannot
  object.
- Con: one feature is spread across four or five folders; a change to
  registration touches all of them, and no folder shows what the feature
  consists of.
- Con: deleting a feature means hunting fragments across packages.

## B - Package by feature

```
registration/  Controller, Service, Request/Response, Exception
login/
token/
user/          User, Role, UserRepository       (shared domain)
config/        SecurityConfig                   (cross-cutting)
shared/        ApiExceptionHandler              (cross-cutting)
```

- Pro: a feature's classes sit in one package, so they can be
  **package-private**. `RegistrationService` is not public; `login/`
  physically cannot call it, enforced at compile time.
- Pro: one feature = one folder. Changing, reviewing or deleting it is
  local.
- Con: less familiar; requires deciding what counts as a feature.
- Con: genuinely shared code needs a home, so it is not purely
  by-feature - `user/`, `config/` and `shared/` are organised by type.

## C - Hexagonal / onion (ports and adapters)

Domain classes with no framework annotations, plus separate persistence
entities and mappers.

- Pro: domain logic testable with no Spring and no database.
- Pro: storage is swappable behind a port.
- Con: doubles the class count for CRUD-shaped services, where the
  "domain logic" is validation plus persistence. auth-service would gain
  a `UserJpaEntity` and a mapper that exist only to satisfy the pattern.
- Con: no service currently justifies it. inventory-service and
  booking-service plausibly will - see Revisit When.

# Decision

**Option B - package by feature, with cross-cutting concerns by type.**

```
com.ticketmaster.<service>/
├── <Service>Application.java
├── config/      framework wiring (security, clients, beans)
├── shared/      cross-cutting code every feature uses
├── <domain>/    shared domain model + repositories (e.g. user/)
└── <feature>/   one folder per feature, all its layers inside
```

Rules:

1. **A feature's classes are package-private unless something outside
   the feature genuinely needs them.** The service class in particular
   should not be public. This is the point of the layout, not a
   side effect.
2. **Do not sub-divide a feature by layer.** `registration/controller/`
   and `registration/service/` are separate packages again, which forces
   the service back to public and discards the only real benefit.
3. **A feature that outgrows one folder splits by sub-feature, not by
   file type** - `token/keys/`, `token/jwks/`, never `token/service/`.
4. **Layer stays visible in the file name** (`RegistrationController`,
   `RegistrationService`), so the structure is still readable at a
   glance without dedicated folders.
5. **One error shape per service**: a `@RestControllerAdvice` in
   `shared/` returning RFC 9457 `ProblemDetail` for every error.
   Per-controller `@ExceptionHandler` methods produce inconsistent
   shapes, and ADR-034 publishes the inconsistency as contract.
6. **Lombok `@Getter` is fine; `@Data`, `@Setter`, `@ToString` and
   `@EqualsAndHashCode` are not permitted on JPA entities.** `@ToString`
   prints a password hash into any log line touching the entity;
   `@EqualsAndHashCode` covers every field, so hashCode changes as JPA
   populates them and equality can trigger a lazy load; `@Setter`
   defeats the constructor that establishes invariants.

# Why

The deciding argument is enforcement. Package-by-layer can only ask
developers not to reach across boundaries; package-by-feature makes the
reach fail to compile. That matches how this vault has consistently
chosen - ADR-002 kept a unique constraint as a backstop rather than
trusting the lock, ADR-025 colocated the body hash with the row rather
than trusting callers.

Hexagonal was rejected on cost, not principle. For a service whose
domain rules amount to "validate, hash, insert", the mapper layer is
pure ceremony. That calculation changes where real invariants live, and
the ADR says so rather than pretending one answer fits all 15.

# Consequences

- auth-service was restructured to this layout (commit `6535dc7`) and is
  the reference implementation. New services copy its shape.
- `RegistrationService` is package-private; its tests exercise it
  through the controller, which is the right level anyway - they assert
  HTTP behaviour rather than internal calls.
- Shared domain needs judgement: `user/` is shared because several
  features need it. Guessing wrong is cheap to correct while a service
  is small and expensive once many features depend on the guess.
- Cross-cutting folders (`config/`, `shared/`) are organised by type, so
  the layout is a hybrid. Stated explicitly here so it does not read as
  drift later.

# Revisit When

- **inventory-service or booking-service is designed.** They hold the
  real invariants in this system (seat state transitions, ADR-006 saga
  compensation, ADR-002 lock ordering). If those rules end up entangled
  with JPA annotations and untestable without a container, that is the
  signal to adopt Option C for those two services specifically - and to
  supersede this ADR with one that states the boundary criterion rather
  than applying hexagonal everywhere by fashion.
- A feature folder exceeds roughly 10 files and rule 3's sub-feature
  split stops being obvious.
