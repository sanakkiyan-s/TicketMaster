import { setupWorker } from "msw/browser";

import { handlers } from "./handlers";

/**
 * Dev-only. Started from main.tsx behind `import.meta.env.DEV`, never
 * bundled into a production build (Vite tree-shakes the dynamic import
 * away). Intercepts the booking endpoints inventory-service will one day
 * serve for real — see handlers.ts's header for why this is safe to
 * delete wholesale once that service exists.
 */
export const worker = setupWorker(...handlers);
