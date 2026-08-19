import React from "react";
import ReactDOM from "react-dom/client";
import "./index.css";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { BrowserRouter, Routes, Route, Link, useNavigate } from "react-router-dom";
import { Loader2 } from "lucide-react";

import { Button } from "@/components/ui/button";
import { LoginPage } from "@/features/auth/LoginPage";
import { RegisterPage } from "@/features/auth/RegisterPage";
import { ProtectedRoute } from "@/features/auth/ProtectedRoute";
import { useLogout } from "@/features/auth/useAuthMutations";
import { useSilentRefresh } from "@/features/auth/useSilentRefresh";
import { useAuthStore } from "@/stores/auth";

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // Seat availability is pushed over SSE (lib/gateway.ts), not
      // polled — refetch-on-focus would fight that stream and re-show
      // stale occupancy. Live data comes from the stream; queries carry
      // the slower-moving catalog data.
      refetchOnWindowFocus: false,
      retry: 1,
    },
  },
});

function Placeholder() {
  return (
    <main className="auth-backdrop mx-auto flex min-h-dvh max-w-2xl flex-col items-start justify-center gap-4 px-6">
      <h1 className="text-3xl font-semibold tracking-tight">TicketMaster</h1>
      <p className="text-muted-foreground">
        Phase 1 scaffold. Catalog and booking routes land here as those services
        come online — see second-brain/wiki/architecture/implementation-roadmap.md.
      </p>
      <p className="flex gap-4 text-sm">
        <Link to="/login" className="font-medium underline underline-offset-4">
          Sign in
        </Link>
        <Link to="/register" className="font-medium underline underline-offset-4">
          Create account
        </Link>
      </p>
    </main>
  );
}

/**
 * Placeholder for the first authenticated surface.
 *
 * This is also, for now, the only reachable place to host a sign-out
 * control — the app doesn't have a real authenticated shell/nav yet, and
 * building one is out of scope for wiring up logout.
 */
function Account() {
  const navigate = useNavigate();
  const user = useAuthStore((state) => state.user);
  const logout = useLogout(() => navigate("/login", { replace: true }));

  return (
    <main className="auth-backdrop mx-auto flex min-h-dvh max-w-2xl flex-col justify-center gap-4 px-6">
      <h1 className="text-3xl font-semibold tracking-tight">Your account</h1>
      <p className="text-muted-foreground">
        {/* user can be null here — e.g. session recovered via silent refresh,
            which gets an access token back but no user object. */}
        {user ? `Signed in as ${user.email}.` : "Signed in."} Tickets and
        transfers arrive with ticket-service.
      </p>
      <Button
        type="button"
        variant="outline"
        className="w-fit"
        disabled={logout.isPending}
        onClick={() => logout.mutate()}
      >
        {logout.isPending ? (
          <>
            <Loader2 aria-hidden="true" className="animate-spin" />
            Signing out…
          </>
        ) : (
          "Sign out"
        )}
      </Button>
    </main>
  );
}

/**
 * Gate for the whole app, not just protected routes.
 *
 * Runs silent refresh once before anything renders past this point, so
 * ProtectedRoute's redirect check sees a recovered session (if any) instead
 * of the momentarily-empty store every reload starts with. The loading
 * state is brief — one network round trip — and intentionally has no
 * branding beyond staying blank, since a reload should feel instant rather
 * than flashing a login page and then swapping to authenticated content.
 */
function SessionGate({ children }: { children: React.ReactNode }) {
  const { isLoading } = useSilentRefresh();

  if (isLoading) {
    return <div className="auth-backdrop min-h-dvh" aria-busy="true" />;
  }

  return <>{children}</>;
}

ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <SessionGate>
          <Routes>
            <Route path="/" element={<Placeholder />} />
            <Route path="/login" element={<LoginPage />} />
            <Route path="/register" element={<RegisterPage />} />

            {/* Everything nested here requires a session. */}
            <Route element={<ProtectedRoute />}>
              <Route path="/account" element={<Account />} />
            </Route>
          </Routes>
        </SessionGate>
      </BrowserRouter>
    </QueryClientProvider>
  </React.StrictMode>,
);
