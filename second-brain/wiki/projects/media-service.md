---
title: media-service
type: project
sources: []
related: [[event-service]], [[infra]], [[ADR-017-media-service-video]], [[ADR-016-multi-region-cdn]]
created: 2026-08-06
last-updated: 2026-08-06
---

## Purpose

Handles media assets that don't fit any existing service's data model:
event images, video trailers, and their transcoding pipeline. Added
2026-08-06 when the user requested a video-trailer feature for events —
exposed a real, previously-missing piece (object storage) rather than
being a cosmetic addition. Full reasoning: [[ADR-017-media-service-video]].

## Current Implementation

Not started. `backend/media-service` does not yet exist as a directory —
15th backend service, added after the original 14.

## Target Design

- Spring Boot, consumes an upload-complete notification from object
  storage (S3/MinIO) via a Kafka job topic, runs FFmpeg to produce
  multiple video renditions (240p/480p/720p/1080p) + an HLS manifest.
- Does not receive uploaded bytes through its own request path —
  event-service issues pre-signed upload URLs, browser uploads directly
  to object storage.
- Emits `media.transcoding.completed`/`media.transcoding.failed` for
  event-service to consume and flip trailer status.
- CPU-bound, long-running job profile — deliberately separate scaling
  characteristics from every other service, which is the stated
  justification for it being its own service rather than folded into
  event-service.

## Gap

Everything.

## Open Questions

- Managed transcoding (Mux/MediaConvert) vs. self-hosted FFmpeg — decided
  toward self-hosted for learning value, flagged in
  [[ADR-017-media-service-video]] as revisitable if operational cost
  proves too high for solo iteration.

Rendition ladder, HLS segment duration, and upload limits (2GB / 3min /
MP4+MOV) resolved in [[ADR-017-media-service-video]]'s amendment.
