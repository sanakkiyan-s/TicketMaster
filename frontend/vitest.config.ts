import { fileURLToPath } from "node:url";
import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

// Separate from vite.config.ts on purpose: that file's `defineConfig` comes
// from "vite", which doesn't know the `test` field, and it also wires up the
// dev-server API proxy (gateway target, env loading) that tests have no use
// for. Sharing just the plugin + alias here keeps `@/*` imports working in
// tests without dragging in dev-server-only config.
export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
    },
  },
  test: {
    environment: "jsdom",
    setupFiles: ["./src/test/setup.ts"],
  },
});
