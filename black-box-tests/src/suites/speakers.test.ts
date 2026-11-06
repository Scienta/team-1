import { beforeAll, describe, expect, it } from "vitest";
import { expectJson, expectStatus } from "../helpers/expect.js";
import {
  createSpeaker,
  getSpeaker,
  postSpeakerRaw,
} from "../helpers/factories.js";
import { get, waitForHealth } from "../helpers/http.js";
import { uniqueEmail, uniqueName } from "../helpers/ids.js";
import { seedBaseline, type BaselineSeed } from "../helpers/seed.js";
import type { Speaker } from "../helpers/types.js";

describe("speakers", () => {
  let baseline: BaselineSeed;

  beforeAll(async () => {
    await waitForHealth();
    baseline = await seedBaseline();
  });

  it("registers a speaker and reads it back exactly", async () => {
    const name = uniqueName("Ada");
    const email = uniqueEmail("ada");
    const bio = "Wrote the first program.";
    const created = await createSpeaker({ name, email, bio });
    expect(created).toMatchObject({ name, email, bio });
    expect(created.id).toEqual(expect.any(Number));
    expect(await getSpeaker(created.id)).toEqual(created);
  });

  it("allows a missing bio and returns null", async () => {
    const created = await createSpeaker({ name: uniqueName("Grace"), email: uniqueEmail("grace") });
    expect(created.bio).toBeNull();
    expect(await getSpeaker(created.id)).toEqual(created);
  });

  it("requires a real name and a real email", async () => {
    const email = uniqueEmail("need");
    expectStatus(await postSpeakerRaw({}), 400);
    expectStatus(await postSpeakerRaw({ email }), 400);
    expectStatus(await postSpeakerRaw({ name: "   ", email }), 400);
    expectStatus(await postSpeakerRaw({ name: "Ada" }), 400);
    expectStatus(await postSpeakerRaw({ name: "Ada", email: "  " }), 400);
    expectStatus(await postSpeakerRaw({ name: "Ada", email: "not-an-email" }), 400);
  });

  it("limits bio to 500 characters", async () => {
    expectStatus(
      await postSpeakerRaw({ name: "Ada", email: uniqueEmail("bio-ok"), bio: "b".repeat(500) }),
      201,
    );
    expectStatus(
      await postSpeakerRaw({ name: "Ada", email: uniqueEmail("bio-bad"), bio: "b".repeat(501) }),
      400,
    );
  });

  it("treats emails as unique regardless of letter case", async () => {
    const email = uniqueEmail("case");
    await createSpeaker({ name: "One", email });
    expectStatus(await postSpeakerRaw({ name: "Two", email: email.toUpperCase() }), 409);
  });

  it("returns 404 for an unknown speaker", async () => {
    expectStatus(await get("/speakers/987654321"), 404);
  });

  it("supports legacy trailing-slash URLs", async () => {
    const created = expectJson(
      await postSpeakerRaw(
        { name: uniqueName("Slash"), email: uniqueEmail("slash") },
        "/speakers/",
      ),
      201,
    ) as Speaker;
    expect(await getSpeaker(created.id, "/")).toEqual(created);
  });

  it("survives concurrent registrations of one email with a single winner", async () => {
    const email = uniqueEmail("race");
    const results = await Promise.all(
      Array.from({ length: 20 }, (_, i) =>
        postSpeakerRaw({
          name: `Racer ${i}`,
          email: i % 2 === 0 ? email : email.toUpperCase(),
        }),
      ),
    );
    for (const res of results) {
      expect([201, 409]).toContain(res.status);
    }
    expect(results.filter((r) => r.status === 201)).toHaveLength(1);
  });

  it("can read the shared baseline speaker (read-only seed)", async () => {
    expect(await getSpeaker(baseline.speaker.id)).toEqual(baseline.speaker);
  });
});
