---
title: ADR-021 "Notify Me" Signups and Mass Broadcast On-Sale Alerts
type: decision
sources: []
related: [[event-service]], [[notification-service]], [[queue-service]], [[venue-service]], [[ADR-004-redis-cluster-sharding]], [[ADR-007-kafka-event-schema]]
created: 2026-08-06
last-updated: 2026-08-06
---

Status: Accepted

# Context

[[ADR-004-redis-cluster-sharding]] uses `presale signups > 3x venue
capacity` as the trigger criteria for flagging a session high-demand —
but no feature anywhere in the vault actually captures presale signups.
It was referenced as if it already existed. Separately, `notification-
service` is scoped only for per-user transactional alerts (your booking
confirmed, your payment succeeded) — there is no design for notifying a
large subscriber base that an event just went on sale, the scenario a
real ticketing platform needs constantly (an artist announces a show,
thousands want to know the instant tickets are available).

Both gaps are the same missing feature: a "Notify Me" signup, captured
per **session** (not per event — a multi-date tour needs independent
signal per show, matching ADR-004's existing `{sessionId}` granularity).

# Requirements / Constraints

- Signup capture must be per-session, feeding ADR-004's high-demand flag
  computation directly — not a separate, disconnected feature.
- Must support anonymous signups (email only, no account) as well as
  logged-in users, matching real-world "notify me" UX.
- Broadcast at on-sale time must not mean iterating and individually
  calling a push provider 50M times — needs a real fan-out mechanism.
- Must feed people into [[queue-service]]'s existing admission-
  throttling design, not attempt to solve the post-notification stampede
  itself — that problem is already solved elsewhere.

# Options Considered

## Push technology: native FCM/APNs vs. plain Web Push API

`frontend.md` specifies a React web client, not a native mobile app —
naive reading says "use Web Push API" (browser-native, no vendor). But
plain Web Push has no topic/fan-out primitive — sending to N subscribers
still means N individual calls to the push service, one per subscriber.

**Decision: use Firebase Cloud Messaging (FCM) even for the web client.**
FCM supports "FCM for Web" — it wraps the same Web Push protocol
browsers already use, but adds genuine **topic-based fan-out**: subscribe
a browser's push registration to a topic, send one message to that topic,
FCM delivers to every subscriber. Also extensible for free if a native
app is ever added later — same provider, same topic mechanism, no
redesign. Resolves last session's flagged ambiguity ("push" was never
specified which technology) concretely.

# Decision

## Signup capture — event-service owns it, per session

```sql
CREATE TABLE session_notify_me (
  id                       UUID NOT NULL,
  event_id                 UUID NOT NULL,   -- Citus distribution column
  session_id               UUID NOT NULL,
  user_id                  UUID,            -- nullable, anonymous OK
  contact_email_ciphertext BYTEA,           -- required if user_id NULL,
                                             -- see amendment below —
                                             -- NEVER plaintext
  fcm_topic_subscribed     BOOLEAN DEFAULT false,
  created_at               TIMESTAMPTZ DEFAULT now(),

  PRIMARY KEY (event_id, id),
  UNIQUE (event_id, session_id, COALESCE(user_id::text, contact_email_ciphertext::text))
);
```

Anonymous signups get email-only fallback delivery (no push topic to
subscribe to); logged-in users with push permission granted get
subscribed to an FCM topic named `session_{sessionId}` at signup time —
subscription happens client-side (the browser registers for the topic
directly with FCM), event-service just records that it happened.

**Amendment: two gaps closed — Citus shard key, and a real residency/GDPR
violation.**

1. **Shard key.** `event_id` added, matching this table's owning session
   (`session_id` belongs to an event) — same colocation reasoning as
   ADR-006/ADR-020. The original `UNIQUE` constraint was per-shard-only
   without it.

2. **Residency/crypto-shredding violation, more serious.** This table
   lives in `event-service`, homed to the *event's* region
   ([[ADR-005-postgres-sharding]]). A US-resident anonymously signing up
   for an EU show would have their email sitting in an EU shard as
   **plaintext** — directly violating [[ADR-016-multi-region-cdn]]'s
   Class R rule (PII is home-region-only, never leaves) and structurally
   unreachable by [[ADR-013-gdpr-crypto-shredding]]'s erasure saga, which
   keys everything on `subject_id` — an anonymous signup has none.
   Fixed two ways: `contact_email` is now `contact_email_ciphertext`,
   encrypted client-side-of-storage the same way any other PII field is
   under ADR-013 (never persisted as plaintext regardless of region);
   and — for a **logged-in** user's anonymous-feeling signup (still has a
   real `user_id`) — the erasure saga already reaches this row via
   `user_id`, no gap there. The **true anonymous case (no account at
   all, email-only)** has no `subject_id` to encrypt against or erase by
   — this is a genuine open product question, not resolved by encryption
   alone, flagged in Open Questions below rather than silently declared
   fixed.

## Feeding ADR-004's high-demand flag — closes that gap directly

```
sessions table (event-service) gains:
  high_demand              BOOLEAN DEFAULT false
  high_demand_set_at       TIMESTAMPTZ

Periodic job (event-service, not a DB trigger — venue capacity lives in
  a different service's database, so this is a cross-service read):
  for each session with signups since last run:
    signup_count = COUNT(session_notify_me WHERE session_id = X)
    capacity = venue-service's capacity for that session's venue
    if signup_count > 3 * capacity:
      SET high_demand = true, high_demand_set_at = now()
      emit session.flagged_high_demand (Kafka, ADR-007 pattern)
```

[[ADR-004-redis-cluster-sharding]]'s capacity-planner job already reads
"event-service's high-demand-flagged events" — that assumption is now
backed by a real computation instead of an unspecified input. No change
needed to ADR-004 itself, only to what feeds it.

## Broadcast at on-sale time — one message, provider fans out

```
Trigger: organizer marks session on-sale, or a scheduled on-sale
  timestamp arrives (either path emits the same event).

event-service emits: session.on_sale_started {sessionId} (Kafka,
  ADR-007 topic-per-event-type pattern, via the Transactional Outbox
  like every other event in this system).

notification-service consumes it:
  1. Push channel: ONE message sent to FCM topic "session_{sessionId}".
     FCM delivers to every subscribed device — not N individual calls.
     Marked HIGH PRIORITY (wakes the device/service worker immediately,
     rather than waiting for the phone/browser's next natural wake).
     Short TTL (10-15 min, starting default) — FCM discards the message
     if undelivered within that window. Notifying someone after the
     on-sale is already saturated or the drop is over is worse than a
     silent miss; a stale notification is a support complaint, not a
     convenience.
  2. Email fallback: for signups with no push subscription (anonymous
     or push-declined), sent individually via the existing email
     provider — genuinely still N sends, but email providers (SES-class)
     already handle bulk sending efficiently; this isn't the bottleneck
     the video's video is about, only push fan-out was.
```

## Explicit non-goal: this feature does not solve the stampede

The moment of highest risk — "millions tap the notification and rush
the servers at once" — is **not** addressed by this ADR. It's already
solved by [[queue-service]]'s admission throttling and
[[ADR-016-multi-region-cdn]]'s edge waiting room. This feature's only
job is getting the audience informed and INTO that already-built
funnel; conflating "notify people" with "handle the resulting traffic"
would duplicate a problem that already has an owner.

# Why

Capturing signups per-session, in the service that already owns session
data, closes the ADR-004 gap with zero new infrastructure. Choosing FCM
even for a web-only frontend trades a small vendor dependency for a real
fan-out primitive — the alternative (plain Web Push) would mean
notification-service manually iterating potentially millions of
individual push calls at exactly the moment load matters most.

# Consequences

**Easier:** ADR-004's high-demand flag now has a real, working input
instead of an assumed one; broadcast notification is a single Kafka
event and a single FCM topic publish, not a fan-out loop
notification-service has to manage itself; the queue-service handoff is
explicit, avoiding two features quietly trying to solve the same
stampede problem.

**Harder:** event-service takes on a new periodic cross-service job
(reading venue-service's capacity) it didn't have before; FCM becomes a
real external dependency for the web frontend, not just a
theoretical native-app concern; anonymous email-based signups still pay
the no-fan-out cost the FCM path was chosen specifically to avoid.

# Revisit When

- If a native mobile app is ever built — the FCM topic mechanism extends
  to it with zero redesign, confirming this choice rather than requiring
  a new one.
- If email-fallback volume becomes large enough that its lack of
  fan-out becomes a real bottleneck — would need a bulk-email-specific
  design at that point, not before.

## Open Questions

- FCM message TTL (10-15 min) and the `3x venue capacity` threshold's
  interaction with this feature's actual signup volume — both starting
  defaults, need real data once sessions exist.
- Whether push-declined/anonymous users should get an SMS fallback in
  addition to email — not yet decided, product-level choice.
- **True-anonymous (no account) signup erasure** — encrypting
  `contact_email_ciphertext` prevents plaintext residency leakage, but a
  signup with no `user_id`/`subject_id` has no key to erase via ADR-013's
  saga if that person later asks for deletion by email address alone.
  Needs a real answer (e.g. a lookup-by-email-hash erasure path, or
  requiring a lightweight account for any signup) — not resolved by
  encryption alone, genuinely open.
