/**
 * Reads the `sub`/`roles` claims out of an access token's payload, without
 * verifying its signature — the gateway already verified it (ADR-009), and
 * this repo already treats JWT claims as informational rather than a trust
 * boundary in the browser (see useSilentRefresh.ts). Used only to backfill
 * role-gated UI after a reload, where /refresh returns a token but no user.
 */
export interface AccessTokenClaims {
  userId: string;
  roles: string[];
}

const SUBJECT_PREFIX = "user:";

export function decodeAccessTokenClaims(accessToken: string): AccessTokenClaims | null {
  const payload = accessToken.split(".")[1];
  if (!payload) return null;

  try {
    const base64 = payload.replace(/-/g, "+").replace(/_/g, "/");
    const json = atob(base64);
    const claims = JSON.parse(json) as { sub?: string; roles?: string[] };

    if (!claims.sub?.startsWith(SUBJECT_PREFIX)) return null;

    return {
      userId: claims.sub.slice(SUBJECT_PREFIX.length),
      roles: claims.roles ?? [],
    };
  } catch {
    return null;
  }
}
