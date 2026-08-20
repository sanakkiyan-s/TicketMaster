import { afterEach } from "vitest";
import { cleanup } from "@testing-library/react";
import "@testing-library/jest-dom/vitest";

// Without `test.globals: true` in vitest.config.ts, @testing-library/react's
// own auto-cleanup (which detects a global `afterEach`) never registers, so
// every render leaks into the next test's DOM within the same file. Explicit
// cleanup here is the fix — kept in setup.ts rather than per test file so
// nothing has to remember to opt in.
afterEach(() => {
  cleanup();
});
