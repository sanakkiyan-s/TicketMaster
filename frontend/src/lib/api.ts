/**
 * The single HTTP entry point for the app.
 *
 * Every call goes to a relative /api path, never an absolute service URL.
 * vite.config.ts proxies /api to api-gateway, so `frontend.md`'s rule — all
 * API traffic through the gateway, never straight to a backend service — is
 * enforced by there being no other way to make a request.
 */

/** RFC 9457, the one error shape auth-service emits (ADR-037). */
export interface ProblemDetail {
  type?: string;
  title?: string;
  status: number;
  detail?: string;
  /** Present on 400: field name -> constraint message. Never a rejected value. */
  errors?: Record<string, string>;
}

export class ApiError extends Error {
  readonly status: number;
  readonly fieldErrors: Record<string, string>;

  constructor(problem: ProblemDetail) {
    super(problem.title ?? `Request failed (${problem.status})`);
    this.name = "ApiError";
    this.status = problem.status;
    this.fieldErrors = problem.errors ?? {};
  }
}

let authHeader: string | null = null;

/**
 * Set by the auth store. The token is passed in rather than imported, so this
 * module never holds session state — and nothing here can read it back out.
 */
export function setAuthHeader(token: string | null): void {
  authHeader = token ? `Bearer ${token}` : null;
}

export async function apiPost<T>(path: string, body: unknown): Promise<T> {
  const response = await fetch(path, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      ...(authHeader ? { Authorization: authHeader } : {}),
    },
    // Required for the refresh_token cookie to be set and returned. The cookie
    // is httpOnly, so this is the only way it participates at all — JS cannot
    // read or attach it manually, which is the entire point.
    credentials: "include",
    body: JSON.stringify(body),
  });

  if (!response.ok) {
    // A gateway 502 or a network-level failure has no ProblemDetail body, so
    // parsing must not be allowed to mask the real status.
    let problem: ProblemDetail = { status: response.status };
    try {
      problem = { ...problem, ...(await response.json()) };
    } catch {
      problem.title = "The server could not be reached";
    }
    throw new ApiError(problem);
  }

  // 204 and friends have no body; JSON.parse("") throws.
  const text = await response.text();
  return (text ? JSON.parse(text) : null) as T;
}
