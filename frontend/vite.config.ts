import { fileURLToPath } from "node:url";
import { defineConfig, loadEnv } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

// Dev server proxies every /api call to api-gateway. Deliberate:
// `frontend.md` requires ALL API calls go through api-gateway, never
// directly to an individual backend service. One proxy target (not one
// per service) makes violating that rule inconvenient by construction,
// not just by discipline.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "");
  const gateway = env.VITE_GATEWAY_URL ?? "http://localhost:8080";

  return {
    // Tailwind runs as a Vite plugin, not PostCSS. It compiles to a
    // static stylesheet at build time — that is the whole reason it was
    // chosen over CSS-in-JS (`frontend.md`): runtime <style> injection
    // would permanently require `style-src-elem 'unsafe-inline'` in the
    // CSP that index.html carries for ADR-011's PCI SAQ A scope.
    plugins: [react(), tailwindcss()],
    resolve: {
      // Mirrors the `@/*` path in tsconfig.json. Both are required —
      // tsconfig satisfies the type checker, this satisfies the bundler.
      // shadcn components are generated with `@/` imports.
      alias: {
        "@": fileURLToPath(new URL("./src", import.meta.url)),
      },
    },
    server: {
      port: 5173,
      proxy: {
        "/api": {
          target: gateway,
          changeOrigin: true,
        },
      },
    },
    build: {
      sourcemap: true,
    },
  };
});
