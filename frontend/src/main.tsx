import React from "react";
import ReactDOM from "react-dom/client";
import "./index.css";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { BrowserRouter, Routes, Route, Link } from "react-router-dom";

import { LoginPage } from "@/features/auth/LoginPage";
import { ProtectedRoute } from "@/features/auth/ProtectedRoute";
import { RegisterPage } from "@/features/auth/RegisterPage";
import { useSilentRefresh } from "@/features/auth/useSilentRefresh";
import { HomePage } from "@/features/home/HomePage";
import { ArtistsPage } from "@/features/organizer/ArtistsPage";
import { CreateEventPage } from "@/features/organizer/CreateEventPage";
import { EventDetailPage } from "@/features/organizer/EventDetailPage";
import { EventsListPage } from "@/features/organizer/EventsListPage";
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

/**
 * "/" is one route with two faces: HomePage once signed in, a plain landing
 * otherwise. No ProtectedRoute redirect dance needed for the root — signing
 * out just swaps which face renders, in place.
 */
function Root() {
  const accessToken = useAuthStore((state) => state.accessToken);
  return accessToken ? <HomePage /> : <Landing />;
}

function Landing() {
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
            <Route path="/" element={<Root />} />
            <Route path="/login" element={<LoginPage />} />
            <Route path="/register" element={<RegisterPage />} />

            {/*
              Backend enforces ownership (api-gateway's ORGANIZER role gate,
              plus event-service's per-resource organizer_id check) — this
              guard is UX-only, same as every other ProtectedRoute use, not
              a security boundary. No separate role check here since this
              app has no client-side role-routing system anywhere yet
              (brief: don't build one for this slice alone).
            */}
            <Route element={<ProtectedRoute />}>
              <Route path="/organizer/events" element={<EventsListPage />} />
              <Route path="/organizer/events/new" element={<CreateEventPage />} />
              <Route path="/organizer/events/:id" element={<EventDetailPage />} />
              <Route path="/organizer/artists" element={<ArtistsPage />} />
            </Route>
          </Routes>
        </SessionGate>
      </BrowserRouter>
    </QueryClientProvider>
  </React.StrictMode>,
);
