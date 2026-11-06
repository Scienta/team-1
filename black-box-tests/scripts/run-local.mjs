#!/usr/bin/env node
/**
 * Assumes docker compose Postgres is already up.
 * Builds the jar if needed, starts it, waits for /health, runs Vitest, stops the jar we started.
 */
import { spawn, spawnSync } from "node:child_process";
import { existsSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const bbRoot = join(here, "..");
const repoRoot = join(bbRoot, "..");
const jar = join(repoRoot, "target", "cfp.jar");
// Prefer CFP_BASE_URL — Cursor often sets BASE_URL to "/" which is not an API origin.
const rawBase = (process.env.CFP_BASE_URL ?? process.env.BASE_URL)?.trim() ?? "";
const baseUrl = (/^https?:\/\//i.test(rawBase) ? rawBase : "http://localhost:8080").replace(
  /\/$/,
  "",
);

const pgEnv = {
  ...process.env,
  PGHOST: process.env.PGHOST ?? "localhost",
  PGPORT: process.env.PGPORT ?? "5432",
  PGDATABASE: process.env.PGDATABASE ?? "cfp",
  PGUSER: process.env.PGUSER ?? "cfp",
  PGPASSWORD: process.env.PGPASSWORD ?? "cfp",
  PGSSLMODE: process.env.PGSSLMODE ?? "disable",
  GIT_SHA: process.env.GIT_SHA ?? "dev",
};

function log(msg) {
  console.log(`[test:local] ${msg}`);
}

function fail(msg) {
  console.error(`[test:local] ${msg}`);
  process.exit(1);
}

async function sleep(ms) {
  await new Promise((r) => setTimeout(r, ms));
}

async function probeHealth() {
  try {
    const res = await fetch(`${baseUrl}/health`);
    const text = await res.text();
    let json = {};
    try {
      json = JSON.parse(text);
    } catch {
      /* ignore */
    }
    return { status: res.status, json, text };
  } catch (err) {
    return { status: 0, json: {}, text: err instanceof Error ? err.message : String(err) };
  }
}

async function assertPostgresReachableViaHealthOrHint() {
  const probe = await probeHealth();
  if (probe.status === 503 && probe.json?.status === "db-unavailable") {
    fail(
      "App is up but database is unavailable. Start Postgres with: docker compose up -d",
    );
  }
  // If nothing is listening yet, we still proceed to start the jar; a later health wait will fail
  // with a clearer message if Postgres is down.
}

function buildJarIfNeeded() {
  if (existsSync(jar) && process.env.FORCE_BUILD !== "1") {
    log(`using existing ${jar}`);
    return;
  }
  log("building jar with ./mvnw -DskipTests package");
  const mvnw = process.platform === "win32" ? "mvnw.cmd" : "./mvnw";
  const r = spawnSync(mvnw, ["-DskipTests", "package"], {
    cwd: repoRoot,
    stdio: "inherit",
    env: process.env,
    shell: process.platform === "win32",
  });
  if (r.status !== 0) fail("Maven package failed");
  if (!existsSync(jar)) fail(`jar missing after build: ${jar}`);
}

function startJar() {
  log(`starting java -jar ${jar} (BASE_URL=${baseUrl})`);
  const child = spawn("java", ["-jar", jar], {
    cwd: repoRoot,
    env: pgEnv,
    stdio: ["ignore", "pipe", "pipe"],
  });
  child.stdout.on("data", (buf) => process.stdout.write(`[cfp] ${buf}`));
  child.stderr.on("data", (buf) => process.stderr.write(`[cfp] ${buf}`));
  return child;
}

async function waitForOk(timeoutMs = 60_000) {
  const start = Date.now();
  let last = "";
  while (Date.now() - start < timeoutMs) {
    const probe = await probeHealth();
    if (probe.status === 200 && probe.json?.status === "ok") {
      log(`healthy: ${probe.text}`);
      return;
    }
    if (probe.status === 503 && probe.json?.status === "db-unavailable") {
      fail("Database unavailable. Is docker compose Postgres running? (docker compose up -d)");
    }
    last = `${probe.status} ${probe.text}`;
    await sleep(500);
  }
  fail(`Timed out waiting for ${baseUrl}/health. Last: ${last}`);
}

function stopChild(child) {
  if (!child || child.killed || child.exitCode !== null) return;
  log(`stopping jar pid=${child.pid}`);
  child.kill("SIGTERM");
}

async function main() {
  await assertPostgresReachableViaHealthOrHint();
  buildJarIfNeeded();

  const already = await probeHealth();
  if (already.status === 200 && already.json?.status === "ok") {
    fail(
      `${baseUrl} is already healthy. Stop the other process, or run \`npm test\` against it instead of test:local.`,
    );
  }

  const child = startJar();
  let exitCode = 1;
  const shutdown = () => stopChild(child);
  process.on("SIGINT", () => {
    shutdown();
    process.exit(130);
  });
  process.on("SIGTERM", () => {
    shutdown();
    process.exit(143);
  });

  try {
    await waitForOk();
    log("running vitest");
    const vitest = spawnSync("npx", ["vitest", "run"], {
      cwd: bbRoot,
      stdio: "inherit",
      env: { ...process.env, CFP_BASE_URL: baseUrl, BASE_URL: baseUrl },
      shell: process.platform === "win32",
    });
    exitCode = vitest.status ?? 1;
  } finally {
    shutdown();
    await sleep(300);
  }
  process.exit(exitCode);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
