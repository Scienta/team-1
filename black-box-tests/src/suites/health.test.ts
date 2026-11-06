import { beforeAll, describe, expect, it } from "vitest";
import { expectJson } from "../helpers/expect.js";
import { get, waitForHealth } from "../helpers/http.js";
import type { Health } from "../helpers/types.js";

describe("health", () => {
  beforeAll(async () => {
    await waitForHealth();
  });

  it("returns 200 ok with a version when the database answers", async () => {
    const body = expectJson(await get<Health>("/health"), 200);
    expect(body.status).toBe("ok");
    expect(body.version).toEqual(expect.any(String));
    expect(body.version.length).toBeGreaterThan(0);
  });
});
