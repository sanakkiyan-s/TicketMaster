import React from "react";
import ReactDOM from "react-dom/client";
import "./index.css";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { BrowserRouter, Routes, Route, Link } from "react-router-dom";

import { LoginPage } from "@/features/auth/LoginPage";
import { RegisterPage } from "@/features/auth/RegisterPage";
import { ProtectedRoute } from "@/features/auth/ProtectedRoute";

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

/** Placeholder for the first authenticated surface. */
function Account() {
  return (
    <main className="auth-backdrop mx-auto flex min-h-dvh max-w-2xl flex-col justify-center gap-4 px-6">
      <h1 className="text-3xl font-semibold tracking-tight">Your account</h1>
      <p className="text-muted-foreground">
        Signed in. Tickets and transfers arrive with ticket-service.
      </p>
    </main>
  );
}

ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <Routes>
          <Route path="/" element={<Placeholder />} />
          <Route path="/login" element={<LoginPage />} />
          <Route path="/register" element={<RegisterPage />} />

          {/* Everything nested here requires a session. */}
          <Route element={<ProtectedRoute />}>
            <Route path="/account" element={<Account />} />
          </Route>
        </Routes>
      </BrowserRouter>
    </QueryClientProvider>
  </React.StrictMode>,
);
