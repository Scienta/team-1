import { beforeAll, describe, expect, it } from "vitest";
import { expectMatch, expectStatus } from "../helpers/expect.js";
import {
  createSpeaker,
  createTalk,
  getTalk,
  listSpeakerTalks,
  listTalks,
  postTalkRaw,
} from "../helpers/factories.js";
import { get, waitForHealth } from "../helpers/http.js";
import { uniqueName } from "../helpers/ids.js";
import { seedBaseline, type BaselineSeed } from "../helpers/seed.js";
import type { TalkFormat } from "../helpers/types.js";

const SUBMITTED_AT = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}\+00:00$/;
const SUBMITTED_ON = /^\d{4}-\d{2}-\d{2}$/;

const DURATIONS: Record<TalkFormat, number> = {
  LIGHTNING_10: 10,
  LIGHTNING_20: 20,
  PRESENTATION: 45,
  WORKSHOP: 120,
};

describe("talks", () => {
  let baseline: BaselineSeed;

  beforeAll(async () => {
    await waitForHealth();
    baseline = await seedBaseline();
  });

  it("submits a talk that reads back with status and legacy timestamps", async () => {
    const speaker = await createSpeaker();
    const talk = await createTalk({
      speakerId: speaker.id,
      title: "Legacy is a feeling",
      format: "PRESENTATION",
      level: "ADVANCED",
    });
    expect(talk).toMatchObject({
      title: "Legacy is a feeling",
      format: "PRESENTATION",
      level: "ADVANCED",
      speakerId: speaker.id,
      status: "SUBMITTED",
      averageRating: null,
      ratingCount: 0,
    });
    expectMatch(talk.submittedAt, SUBMITTED_AT, "submittedAt");
    expectMatch(talk.submittedOn, SUBMITTED_ON, "submittedOn");
    expect(talk.submittedOn).toBe(talk.submittedAt.slice(0, 10));
    expect(await getTalk(talk.id)).toEqual(talk);
  });

  it("derives duration from format and ignores client durationMinutes", async () => {
    for (const [format, minutes] of Object.entries(DURATIONS) as [TalkFormat, number][]) {
      const speaker = await createSpeaker();
      const talk = await createTalk({
        speakerId: speaker.id,
        format,
        durationMinutes: 999,
      });
      expect(talk.durationMinutes).toBe(minutes);
    }
  });

  it("rejects non-exact formats/levels and missing required fields", async () => {
    const speaker = await createSpeaker();
    const sid = speaker.id;
    expectStatus(await postTalkRaw({ title: "T", format: "presentation", level: "BEGINNER", speakerId: sid }), 400);
    expectStatus(await postTalkRaw({ title: "T", format: "KEYNOTE", level: "BEGINNER", speakerId: sid }), 400);
    expectStatus(await postTalkRaw({ title: "T", format: "PRESENTATION", level: "beginner", speakerId: sid }), 400);
    expectStatus(await postTalkRaw({ title: "T", format: "PRESENTATION", level: "EXPERT", speakerId: sid }), 400);
    expectStatus(await postTalkRaw({ title: "  ", format: "PRESENTATION", level: "BEGINNER", speakerId: sid }), 400);
    expectStatus(await postTalkRaw({ format: "PRESENTATION", level: "BEGINNER", speakerId: sid }), 400);
    expectStatus(await postTalkRaw({ title: "T", level: "BEGINNER", speakerId: sid }), 400);
    expectStatus(await postTalkRaw({ title: "T", format: "PRESENTATION", speakerId: sid }), 400);
    expectStatus(await postTalkRaw({ title: "T", format: "PRESENTATION", level: "BEGINNER" }), 400);
    expectStatus(await postTalkRaw({ title: "T", format: "PRESENTATION", level: "BEGINNER", speakerId: 987654321 }), 400);
  });

  it("lists a speaker's talks oldest first and only their own", async () => {
    const speaker = await createSpeaker();
    const other = await createSpeaker();
    const a = await createTalk({ speakerId: speaker.id, title: "A-" + uniqueName() });
    const b = await createTalk({ speakerId: speaker.id, title: "B-" + uniqueName() });
    await createTalk({ speakerId: other.id, title: "Other-" + uniqueName() });

    const mine = await listSpeakerTalks(speaker.id);
    expect(mine.map((t) => t.id)).toEqual([a.id, b.id]);

    const filtered = await listTalks({ speakerId: speaker.id });
    expect(filtered.map((t) => t.id)).toEqual([a.id, b.id]);
  });

  it("allows at most 3 talks per speaker", async () => {
    const speaker = await createSpeaker();
    await createTalk({ speakerId: speaker.id, title: "1" });
    await createTalk({ speakerId: speaker.id, title: "2" });
    await createTalk({ speakerId: speaker.id, title: "3" });
    expectStatus(
      await postTalkRaw({
        title: "4",
        format: "PRESENTATION",
        level: "BEGINNER",
        speakerId: speaker.id,
      }),
      409,
    );
  });

  it("enforces the 3-talk limit under concurrent submits", async () => {
    const speaker = await createSpeaker();
    const results = await Promise.all(
      Array.from({ length: 10 }, (_, i) =>
        postTalkRaw({
          title: `Concurrent ${i}`,
          format: "LIGHTNING_10",
          level: "BEGINNER",
          speakerId: speaker.id,
        }),
      ),
    );
    for (const res of results) {
      expect([201, 409]).toContain(res.status);
    }
    expect(results.filter((r) => r.status === 201)).toHaveLength(3);
    expect(await listSpeakerTalks(speaker.id)).toHaveLength(3);
  });

  it("returns 404 for unknown talk or speaker talks list", async () => {
    expectStatus(await get("/talks/987654321"), 404);
    expectStatus(await get("/speakers/987654321/talks"), 404);
  });

  it("can read the shared baseline talk (read-only seed)", async () => {
    const talk = await getTalk(baseline.talk.id);
    expect(talk.id).toBe(baseline.talk.id);
    expect(talk.speakerId).toBe(baseline.speaker.id);
  });
});
