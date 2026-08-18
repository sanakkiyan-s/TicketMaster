import { create } from "zustand";
import { setAuthHeader } from "@/lib/api";

export interface User {
  id: string;
  email: string;
  roles: string[];
}

interface AuthState {
  accessToken: string | null;
  user: User | null;
  setSession: (accessToken: string, user: User) => void;
  clearSession: () => void;
}

/**
 * Session state, in memory ONLY.
 *
 * Deliberately not persisted — no localStorage, no sessionStorage, no zustand
 * `persist` middleware. Anything in web storage is readable by any script that
 * achieves XSS, and an access token there is a stolen session.
 *
 * The cost is real and accepted: a page reload loses the access token. That is
 * what the refresh token is for — it lives in an httpOnly cookie the browser
 * replays automatically, so a reload can mint a new access token without the
 * app ever being able to read the long-lived credential. Losing 10 minutes of
 * token on reload is the correct trade against leaking 30 days of one.
 */
export const useAuthStore = create<AuthState>((set) => ({
  accessToken: null,
  user: null,

  setSession: (accessToken, user) => {
    setAuthHeader(accessToken);
    set({ accessToken, user });
  },

  clearSession: () => {
    setAuthHeader(null);
    set({ accessToken: null, user: null });
  },
}));
