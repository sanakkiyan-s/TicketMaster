/**
 * The single door to the backend.
 *
 * `frontend.md` requires every API call to go through api-gateway and
 * never directly to a backend service, and ADR-034 versions that edge at
 * /api/v1. Both rules live here so no feature module has to remember
 * them.
 *
 * Axios is the transport, but it is an IMPLEMENTATION DETAIL: nothing
 * outside this file imports axios, and no axios type appears in any
 * exported signature. Callers bind to `request`, `RequestOptions`,
 * `ApiError` and `NetworkError` only, so the transport can be swapped
 * again without touching a single feature module.
 */

import axios, { AxiosError, type AxiosResponse } from "axios";

const BASE = "/api/v1";

/**
 * Deliberately NOT using axios interceptors.
 *
 * The auth retry below must replay a request with a REBUILT Authorization
 * header but the SAME Idempotency-Key. Done with interceptors, the replay
 * (`api(config)`) re-enters the request interceptor holding the previous
 * attempt's config, so preserving the key depends on remembering `??=`
 * instead of `=` — and getting that wrong double-books a seat silently,
 * with no type error and no failing test. Explicit control flow makes
 * that class of mistake unrepresentable.
 *
 * Interceptors are still fine for logging or telemetry later, where a
 * mistake is not a double charge.
 */
const http = axios.create({
  baseURL: BASE,
  withCredentials: true,
  // Report every HTTP status as a normal resolution. The 401 branch below
  // needs to INSPECT the response and decide, which is clumsy when a
  // status has already been converted into a thrown error.
  validateStatus: () => true,
});

/* ------------------------------------------------------------------ *
 * Errors
 * ------------------------------------------------------------------ */

/** The server answered, with a non-2xx status. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly body: unknown,
  ) {
    super(`API ${status}`);
    this.name = "ApiError";
  }
}

/**
 * No answer at all: offline, DNS failure, timeout, or a caller-triggered
 * abort. Exists so an `AxiosError` never escapes this module — if feature
 * code ever caught axios's own error type, the transport boundary would
 * be gone and swapping axios out would become a codebase-wide change.
 */
export class NetworkError extends Error {
  constructor(
    message: string,
    /** True when a caller's AbortSignal cancelled the request. */
    readonly aborted: boolean,
    override readonly cause?: unknown,
  ) {
    super(message);
    this.name = "NetworkError";
  }
}

function toNetworkError(error: unknown): NetworkError {
  if (axios.isCancel(error)) {
    return new NetworkError("Request aborted", true, error);
  }
  if (error instanceof AxiosError) {
    const timedOut = error.code === AxiosError.ECONNABORTED;
    return new NetworkError(
      timedOut ? "Request timed out" : "Network request failed",
      false,
      error,
    );
  }
  return new NetworkError("Network request failed", false, error);
}

/* ------------------------------------------------------------------ *
 * Tokens (ADR-012)
 * ------------------------------------------------------------------ */

// Access token is held in memory only, never localStorage: a 10-minute
// token that survives in storage is an XSS-exfiltratable bearer token
// for the whole window. The refresh token is opaque and lives in an
// httpOnly cookie set by auth-service, so this module never sees it.
//
// It is also deliberately NOT in a state store. No component renders it,
// so nothing should re-render when it rotates, and a store's persistence
// middleware would silently put it back in localStorage.
let accessToken: string | null = null;

export function setAccessToken(token: string | null): void {
  accessToken = token;
}

/**
 * Refresh, single-flighted.
 *
 * ADR-012 rotates the refresh token on every use and treats a SECOND
 * use of the same token as a stolen-token replay — it invalidates the
 * entire token family and forces re-auth. So if two requests (or two
 * tabs) each notice a 401 and each POST /auth/refresh with the same
 * cookie, the second one looks exactly like an attack and logs the user
 * out everywhere.
 *
 * Collapsing concurrent refreshes into one in-flight promise is
 * therefore a correctness requirement of ADR-012's reuse detection, not
 * a performance nicety.
 *
 * Note this guard is per JS context. Two real browser tabs hold separate
 * module state and can still race; cross-tab coordination (Web Locks or
 * BroadcastChannel) is still open.
 */
let refreshInFlight: Promise<boolean> | null = null;

async function refreshAccessToken(): Promise<boolean> {
  if (refreshInFlight) return refreshInFlight;

  refreshInFlight = (async () => {
    try {
      // The opaque refresh token rides as an httpOnly cookie
      // (withCredentials on the instance); this code never sees it.
      const res = await http.post<{ accessToken: string }>("/auth/refresh");
      if (res.status < 200 || res.status >= 300) {
        setAccessToken(null);
        return false;
      }
      setAccessToken(res.data.accessToken);
      return true;
    } catch {
      // A refresh that never reached the server is not proof the session
      // is dead, but there is no usable token either way. Report failure
      // so the caller surfaces the original error rather than a replay.
      return false;
    } finally {
      refreshInFlight = null;
    }
  })();

  return refreshInFlight;
}

/* ------------------------------------------------------------------ *
 * Idempotency (ADR-025)
 * ------------------------------------------------------------------ */

// ADR-025: the CLIENT generates the key, one per logical attempt, so a
// retry of the same attempt carries the same key while a genuinely new
// attempt gets a new one. Generating it server-side or per-HTTP-request
// would defeat the entire mechanism.
//
// Call this ONCE per user intent (one Buy click), not once per send.
export function newIdempotencyKey(): string {
  return crypto.randomUUID();
}

/* ------------------------------------------------------------------ *
 * Request
 * ------------------------------------------------------------------ */

const DEFAULT_TIMEOUT_MS = 15_000;

/**
 * Transport-agnostic on purpose: these options describe what the CALLER
 * wants, not what axios accepts. Extending axios's own config type would
 * publish axios through the boundary this file exists to maintain.
 */
interface CommonOptions<T> {
  body?: unknown;
  headers?: Record<string, string>;
  /** Caller cancellation; TanStack Query supplies one automatically. */
  signal?: AbortSignal;
  /** Per-attempt budget. A refresh replay gets its own fresh budget. */
  timeoutMs?: number;
  /**
   * Validates and narrows the response body. A Zod schema's `.parse`
   * satisfies this signature directly. Omitting it falls back to an
   * unchecked cast, so pass one on anything that books, charges, or
   * authenticates.
   */
  parse?: (data: unknown) => T;
}

interface ReadOptions<T> extends CommonOptions<T> {
  method?: "GET" | "HEAD";
  /** Reads are replay-safe by definition; a key here is meaningless. */
  idempotencyKey?: never;
}

interface WriteOptions<T> extends CommonOptions<T> {
  method: "POST" | "PUT" | "PATCH" | "DELETE";
  /**
   * REQUIRED, by type. ADR-025 only works when one key spans every retry
   * of one logical attempt, and the caller alone knows where that attempt
   * begins and ends. A silent per-request default would make writes LOOK
   * deduplicated while double-booking on any retry, so omitting it is a
   * compile error rather than a runtime surprise.
   */
  idempotencyKey: string;
}

export type RequestOptions<T> = ReadOptions<T> | WriteOptions<T>;

export async function request<T>(
  path: string,
  options: RequestOptions<T> = {},
): Promise<T> {
  const {
    method = "GET",
    body,
    headers: callerHeaders,
    signal,
    timeoutMs = DEFAULT_TIMEOUT_MS,
    parse,
    idempotencyKey,
  } = options;

  const send = async (): Promise<AxiosResponse<unknown>> => {
    // Rebuilt on EVERY call, never hoisted: the post-refresh replay must
    // read the NEW accessToken. A header object built once outside would
    // still carry the expired token and 401 again.
    const headers: Record<string, string> = {
      ...callerHeaders,
      Accept: "application/json",
    };
    if (body !== undefined) {
      headers["Content-Type"] = "application/json";
    }
    if (accessToken) {
      headers.Authorization = `Bearer ${accessToken}`;
    }
    // Read from the closure, so the replay reuses the caller's key rather
    // than minting a second one.
    if (idempotencyKey) {
      headers["Idempotency-Key"] = idempotencyKey;
    }

    try {
      return await http.request({
        url: path,
        method,
        headers,
        data: body,
        // Per-request, so each attempt gets its own full budget instead
        // of the replay inheriting an already-exhausted clock.
        timeout: timeoutMs,
        signal,
      });
    } catch (error) {
      throw toNetworkError(error);
    }
  };

  let res = await send();

  // One refresh attempt, then replay the original request. The replay
  // reuses the same Idempotency-Key, so a write that actually landed
  // before the 401 is not applied twice. Exactly one retry — a loop here
  // would hammer auth-service whenever refresh is genuinely broken.
  if (res.status === 401 && (await refreshAccessToken())) {
    res = await send();
  }

  if (res.status < 200 || res.status >= 300) {
    // res.data may be a parsed object, or a string when an edge proxy
    // returns HTML. Both are more useful to the UI than discarding it.
    throw new ApiError(res.status, res.data ?? null);
  }
  if (res.status === 204) return undefined as T;

  return parse ? parse(res.data) : (res.data as T);
}

/* ------------------------------------------------------------------ *
 * Live seat availability (ADR-022, seat-availability-live-updates)
 * ------------------------------------------------------------------ */

export interface SeatStatusEvent {
  seatId: string;
  status: "AVAILABLE" | "HELD" | "PURCHASED";
  /** Server-supplied; used to drop events older than current state. */
  occurredAt: string;
}

/**
 * Subscribes to a session's seat-status stream.
 *
 * Stays on the native EventSource: SSE is not an axios capability, so
 * this half of the file is unaffected by the transport choice above.
 *
 * ADR-022 caps concurrent SSE connections per gateway instance and fails
 * CLOSED at capacity, so a rejected subscription is an expected outcome,
 * not an error to hide: `onUnavailable` exists to make the caller decide
 * what the UI does (fall back to polling the REST seat map) rather than
 * silently showing a stale map.
 *
 * ADR-016 splits seat-map GEOMETRY (CDN-cacheable, fetched over REST)
 * from OCCUPANCY (live, this stream). Do not fold them back together.
 */
export function subscribeToSeatStatus(
  sessionId: string,
  handlers: {
    onEvent: (event: SeatStatusEvent) => void;
    onUnavailable?: () => void;
  },
): () => void {
  const source = new EventSource(
    `${BASE}/sessions/${encodeURIComponent(sessionId)}/seat-status`,
    { withCredentials: true },
  );

  source.onmessage = (message) => {
    handlers.onEvent(JSON.parse(message.data) as SeatStatusEvent);
  };

  source.onerror = () => {
    // EventSource auto-reconnects on transient errors; a CLOSED
    // readyState means the server refused us (capacity cap) and the
    // caller must degrade deliberately.
    if (source.readyState === EventSource.CLOSED) {
      handlers.onUnavailable?.();
    }
  };

  return () => source.close();
}
