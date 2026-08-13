---
title: ADR-017 Media Service — Object Storage, Video Trailers, Transcoding
type: decision
sources: []
related: [[event-service]], [[infra]], [[ADR-016-multi-region-cdn]]
created: 2026-08-06
last-updated: 2026-08-06
---

Status: Accepted

# Context

The stack has no object storage anywhere — a real gap, not a stylistic
one, since event posters, ticket PDFs/QR codes, and now video trailers
are all binary blobs that must never live in Postgres (bloats the DB,
kills backup/replication times, and [[ADR-005-postgres-sharding]]'s
sharding makes it worse). User requested adding an event-trailer video
feature; video is the one feature that forces this gap to be addressed
for real, since it can't be faked with a URL column alone.

# Requirements / Constraints

- Uploading a large video must not stream through a Spring Boot
  request thread — ties up threads, blows request-size limits.
- A raw uploaded file is not directly playable at scale — different
  devices/networks need different bitrates.
- Must not become a 15th service without independent justification, per
  [[ADR-001-microservices-vs-modular-monolith]]'s standard.

# Decision

## Storage — S3-compatible object storage (MinIO locally, S3 in prod)

Postgres (`event-service`) stores metadata only: `trailer_asset_id`,
`status`, `duration`, `thumbnail_key`. Object storage holds the actual
bytes. CDN in front of it (per [[ADR-016-multi-region-cdn]]) — video is
heavy and globally viewed; serving straight from origin is slow far from
that region and expensive. Mandatory here, not optional, unlike the
CDN's optional role for most other assets.

## Upload flow

```
1. Organizer requests upload -> event-service returns a PRE-SIGNED
   upload URL (direct to object storage).
2. Browser uploads DIRECTLY to object storage — never through
   event-service. A 500MB file must not touch the app tier.
3. Object storage fires an upload-complete notification.
4. Transcoding job runs (below).
5. event-service flips status: UPLOADED -> PROCESSING -> READY (or
   FAILED).
6. Trailer only shows on the event page when status = READY.
```

## Transcoding — cannot be skipped

A raw upload is one file at one bitrate; a phone on 4G and a desktop on
fiber need different things. Transcoding produces multiple renditions
(240p/480p/720p/1080p) + an HLS manifest for adaptive playback.

**Self-hosted FFmpeg worker, consuming a Kafka job topic** — chosen over
a managed service (AWS MediaConvert / Mux / Cloudflare Stream) for this
project's learning goal: it's just another async Kafka consumer, the same
shape as every other one already designed under
[[ADR-007-kafka-event-schema]]. It's also naturally slow (minutes), which
is exactly why it must be async and status-tracked, never blocking the
organizer's request — reinforces rather than contradicts the rest of the
architecture's async-for-slow-work pattern.

## Where the transcoder lives: new service, `media-service`

Justified against [[ADR-001-microservices-vs-modular-monolith]]'s
standard, not by default: transcoding has a completely different resource
profile (CPU-bound, long-running, needs the FFmpeg binary, wants to
scale to zero when idle) than event-service's short request/response
work. Folding it into event-service would mean scaling event-service on
video load — wrong coupling. `media-service` consumes an upload-complete
event, runs FFmpeg, writes renditions back to object storage, emits
`media.transcoding.completed`/`failed` for event-service to consume.

# Why

Object storage is a real, previously-missing piece needed regardless of
video (ticket PDFs/QR codes and event images already needed it). Video
specifically forces the async-job pattern because transcoding is slow —
consistent with, not a departure from, this project's established
approach to slow work (Saga compensation, Debezium CDC, DLQ retries all
share the same "never block the synchronous request path" principle).

# Consequences

**Easier:** event images, ticket PDFs, and video trailers all use one
consistent storage/CDN pattern instead of three ad-hoc ones; media-service
can scale/fail independently of event-service without affecting browsing
or booking.

**Harder:** a 15th service to build and operate; FFmpeg transcoding is
genuinely resource-intensive (CPU/memory spikes) and needs its own
capacity planning, separate from every other service's profile; upload
failure/retry handling (partial uploads, corrupt files) needs real
design attention at implementation time.

# Revisit When

- If self-hosted FFmpeg proves too operationally heavy for solo
  iteration speed — a managed transcoding service (Mux, MediaConvert) is
  a straightforward swap behind the same job-topic interface, losing only
  the "build it yourself" learning value, not correctness.

## Amendment: rendition ladder, upload limits, formats (resolved)

```
Rendition ladder (standard streaming ladder, not invented for this
  project — matches common HLS practice):
    240p  ~400 kbps   -- slow/constrained connections
    480p  ~1 Mbps     -- default mobile
    720p  ~2.5 Mbps   -- default desktop/wifi
    1080p ~5 Mbps     -- high-bandwidth only
  HLS segment duration: 6 seconds (standard tradeoff — short enough for
    fast quality switching, long enough to avoid segment-request
    overhead dominating).

Upload limits:
  Max file size: 2 GB
  Max duration: 3 minutes (a trailer, not a full recording — also
    bounds worst-case transcoding time)
  Accepted input formats: MP4 (H.264/AAC) and MOV — the two formats
    every common device/editor already exports. Transcoded output is
    ALWAYS H.264/AAC HLS regardless of input codec, so input format
    only affects whether FFmpeg re-encodes or can partially copy
    streams — not a correctness constraint, just an efficiency one.
```

*Starting defaults, same category as every other numeric tunable in this
vault* — the real numbers should come from actual trailer content once
organizers start uploading, not guessed permanently. Flagging this
explicitly rather than treating it as a final, load-tested number (unlike
ADR-008's E1-E11, there's no dedicated experiment for this — it's a
product/content decision, not a systems-performance one).

## Amendment: chunked, resumable, deduplicated upload (2026-08-13)

**Gap found**: the original upload flow above is single-shot — one
presigned URL, one PUT, the whole file in one request. At a 2GB cap this
is smaller-blast-radius than a general-purpose 50GB case, but it was
never a deliberate "small enough not to need this" call — it was just
absent. A dropped connection at 1.9GB currently means starting over from
zero. Closing this properly, reusing S3's real multipart mechanism
rather than inventing one.

```
Client-side, before any network call:
  1. Chunk the file into 5-10MB pieces.
  2. Compute a SHA-256 fingerprint of the WHOLE file, and one per chunk.
     Fingerprint identifies CONTENT, not the upload attempt — two
     organizers uploading the identical trailer file produce the same
     whole-file fingerprint (useful for dedup), while trailer_asset_id
     stays a UUID per upload record regardless.

Check-before-upload:
  2. Client sends the whole-file fingerprint to event-service. If a
     record with that fingerprint already exists and status=uploading,
     return its existing chunk statuses — client resumes, uploads only
     the chunks still marked not-uploaded. This is the resumability the
     original design had no way to offer at all.

Initiate (first-time only):
  3. event-service calls S3's CreateMultipartUpload, gets an uploadId,
     generates one presigned URL per chunk (not one for the whole file
     — each part needs its own), writes a trailer_asset record with
     status=uploading and a per-chunk array, each chunk tracked as
     {chunkId, status: not-uploaded|uploading|uploaded, etag}.

Upload:
  4. Client PUTs each chunk directly to S3 via its own presigned URL —
     same "never touches the app tier" property the original design
     already had, just per-chunk instead of per-file.
  5. After each chunk succeeds, client PATCHes event-service with the
     chunk's status + S3-returned ETag. event-service verifies against
     S3's ListParts API before marking that chunk uploaded in the
     trailer_asset record — not trusting the client's PATCH alone.

Complete:
  6. Once every chunk in the array reads uploaded, event-service calls
     S3's CompleteMultipartUpload with the part numbers + ETags. Only
     after S3 confirms the assembled object does status flip to
     PROCESSING (handing off to the existing FFmpeg transcoding flow,
     unchanged by this amendment).
```

Progress indicator falls out of this for free — client already knows
which chunks are done at any moment, no separate mechanism needed.

# Why this amendment

Reuses S3's own Multipart Upload API rather than a hand-rolled chunk
protocol — same "generated/managed contract over hand-maintained
mechanism" preference already applied elsewhere in this vault (Avro
schemas over hand-checked JSON, `.proto`/`buf breaking` over manual
contract review). Fingerprint-based dedup/resume is the same idea
[[ADR-025-idempotency-key-policy]] applies to request retries, just at
the file-content layer instead of the request layer — one recognizable
pattern, two places it turned out to be needed independently.

## Open Questions

- None outstanding — resolved above.

## Amendment: cross-organizer dedup rejected (2026-08-13)

**Decided**: the fingerprint check stays scoped per-organizer
(`WHERE organizer_id = ? AND fingerprint = ?`), never global. Two
different organizers uploading the same underlying file each get their
own stored copy.

Considered and rejected sharing one stored object across organizers:
the resumability/wasted-bandwidth problem — the actual reason
fingerprinting exists — is already fully solved per-organizer. Global
dedup would only buy marginal storage savings (trailers are capped at
2GB/3min, cross-organizer byte-identical collisions are rare) in
exchange for a real new problem: shared ownership of one object across
two organizer accounts, undefined behavior on one organizer's
delete/cancel, and a GDPR-adjacent question about whether an erasure
event should touch bytes another organizer also references — none of
which [[ADR-030-organizer-admin-authorization]]'s single-owner
`organizer_id` model is built to answer. Not worth opening that surface
for a marginal win — same YAGNI instinct already applied to rejecting a
policy service (ADR-030) and a dedicated cancellation service (ADR-028).
