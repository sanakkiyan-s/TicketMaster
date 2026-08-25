import React from "react";
import ReactDOM from "react-dom/client";
import "./index.css";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { BrowserRouter, Routes, Route } from "react-router-dom";

import { SeatSelectionPage } from "@/features/booking/SeatSelectionPage";
import { LoginPage } from "@/features/auth/LoginPage";
import { ProtectedRoute } from "@/features/auth/ProtectedRoute";
import { RegisterPage } from "@/features/auth/RegisterPage";
import { useSilentRefresh } from "@/features/auth/useSilentRefresh";
import { BrowsePage } from "@/features/browse/BrowsePage";
import { EventDetailPage as PublicEventDetailPage } from "@/features/browse/EventDetailPage";
import { HomePage } from "@/features/home/HomePage";
import { ArtistsPage } from "@/features/organizer/ArtistsPage";
import { CreateEventPage } from "@/features/organizer/CreateEventPage";
import { EventDetailPage } from "@/features/organizer/EventDetailPage";
import { EventsListPage } from "@/features/organizer/EventsListPage";

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

/**
 * inventory-service (booking/checkout) doesn't exist yet (ADR-036 Phase
 * 3). Dev-only: starts MSW's browser worker so features/booking's pages
 * work against src/mocks/handlers.ts instead of a 404. Never runs in a
 * production build — Vite drops this whole branch as dead code once
 * `import.meta.env.DEV` is statically false.
 */
async function enableMocking(): Promise<void> {
  if (!import.meta.env.DEV) return;
  const { worker } = await import("./mocks/browser");
  await worker.start({ onUnhandledRequest: "bypass" });
}

enableMocking().then(() => {
  ReactDOM.createRoot(document.getElementById("root")!).render(
    <React.StrictMode>
      <QueryClientProvider client={queryClient}>
        <BrowserRouter>
          <SessionGate>
            <Routes>
              {/*
                Public storefront — works signed in or out, same as real
                Ticketmaster's homepage. Account/organizer surfaces moved to
                their own routes below instead of living at "/".
              */}
              <Route path="/" element={<BrowsePage />} />
              <Route path="/events/:eventId" element={<PublicEventDetailPage />} />
              <Route path="/login" element={<LoginPage />} />
              <Route path="/register" element={<RegisterPage />} />

              {/*
                Backend enforces ownership (api-gateway's ORGANIZER role gate,
                plus event-service's per-resource organizer_id check) — this
                guard is UX-only, same as every other ProtectedRoute use, not
                a security boundary. Role gating in the UI (organizer link,
                organizer-dashboard card) is informational only, not this
                guard's job.
              */}
              <Route element={<ProtectedRoute />}>
                <Route path="/account" element={<HomePage />} />
                <Route
                  path="/events/:eventId/sessions/:sessionId/seats"
                  element={<SeatSelectionPage />}
                />
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
});
