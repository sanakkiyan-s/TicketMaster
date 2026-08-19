import { fileURLToPath } from "node:url";
import { defineConfig, loadEnv, type Plugin } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

// Dev server proxies every /api call to api-gateway. Deliberate:
// `frontend.md` requires ALL API calls go through api-gateway, never
// directly to an individual backend service. One proxy target (not one
// per service) makes violating that rule inconvenient by construction,
// not just by discipline.

/**
 * Dev-only CSP relaxation. `index.html`'s CSP (ADR-011's PCI SAQ A
 * baseline) sets `style-src-elem 'self'`, no `unsafe-inline` - correct
 * for a production build, where Tailwind compiles to a static
 * `<link>`-ed stylesheet that satisfies `'self'` outright. But
 * `vite dev` always injects CSS via a JS-driven `<style>` tag for HMR,
 * regardless of what compiled it - that injected tag has no origin and
 * gets silently dropped by the strict policy, which is why the page
 * rendered with zero styling (2026-08-19: confirmed via `vite build`,
 * which produces a real compiled stylesheet and is unaffected).
 *
 * Patches ONLY the dev-served HTML, never the production build - build
 * output keeps the file's original strict policy untouched.
 */
function devStylesUnderCsp(): Plugin {
  return {
    name: "dev-csp-allow-style-injection",
    apply: "serve",
    transformIndexHtml(html) {
      return html.replace(
        "style-src-elem 'self';",
        "style-src-elem 'self' 'unsafe-inline';",
      );
    },
  };
}

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "");
  const gateway = env.VITE_GATEWAY_URL ?? "http://localhost:8080";

  return {
    // Tailwind runs as a Vite plugin, not PostCSS. It compiles to a
    // static stylesheet at build time — that is the whole reason it was
    // chosen over CSS-in-JS (`frontend.md`): runtime <style> injection
    // would permanently require `style-src-elem 'unsafe-inline'` in the
    // CSP that index.html carries for ADR-011's PCI SAQ A scope. That
    // guarantee holds for `vite build`; `devStylesUnderCsp` above is the
    // dev-server-only exception, since Vite's own HMR mechanism needs it
    // regardless of Tailwind.
    plugins: [react(), tailwindcss(), devStylesUnderCsp()],
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
