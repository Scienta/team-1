import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    globals: true,
    environment: "node",
    include: ["src/suites/**/*.test.ts"],
    testTimeout: 30_000,
    hookTimeout: 60_000,
    // Hikari pool is capped at 5; parallel suite files + in-test concurrency exhaust it.
    fileParallelism: false,
    pool: "forks",
    maxWorkers: 1,
  },
});
