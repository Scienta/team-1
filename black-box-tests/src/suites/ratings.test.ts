import { beforeAll, describe, expect, it } from "vitest";
import { expectMatch, expectStatus } from "../helpers/expect.js";
import {
  createSpeaker,
  createTalk,
  getTalk,
  listSpeakerTalks,
  listTalks,
  putRatingRaw,
  rateTalk,
} from "../helpers/factories.js";
import { get, waitForHealth } from "../helpers/http.js";
import { uniqueReviewerId } from "../helpers/ids.js";
import { seedBaseline, type BaselineSeed } from "../helpers/seed.js";

const RATED_AT = /^\d{1,2}\/\d{1,2}\/\d{2}, \d{1,2}:\d{2} (AM|PM)$/;

describe("ratings", () => {
  let baseline: BaselineSeed;

  beforeAll(async () => {
    await waitForHealth();
    baseline = await seedBaseline();
  });

  it("rates a talk and shows count, average, and ratedAtDisplay", async () => {
    const speaker = await createSpeaker();
    const talk = await createTalk({ speakerId: speaker.id });
    const reviewerId = uniqueReviewerId();
    const rating = await rateTalk(talk.id, 5, reviewerId);
    expect(rating).toMatchObject({ talkId: talk.id, reviewerId, score: 5 });
    expectMatch(rating.ratedAtDisplay, RATED_AT, "ratedAtDisplay");

    const shown = await getTalk(talk.id);
    expect(shown.ratingCount).toBe(1);
    expect(shown.averageRating).toBe(5);
  });

  it("replaces a reviewer's score and rounds average to one decimal (half up)", async () => {
    const speaker = await createSpeaker();
    const talk = await createTalk({ speakerId: speaker.id });
    // 4,4,4,5 → 4.25 → 4.3
    await rateTalk(talk.id, 4, uniqueReviewerId());
    await rateTalk(talk.id, 4, uniqueReviewerId());
    await rateTalk(talk.id, 4, uniqueReviewerId());
    const r4 = uniqueReviewerId();
    await rateTalk(talk.id, 5, r4);
    let shown = await getTalk(talk.id);
    expect(shown.ratingCount).toBe(4);
    expect(shown.averageRating).toBe(4.3);

    await rateTalk(talk.id, 1, r4);
    shown = await getTalk(talk.id);
    expect(shown.ratingCount).toBe(4);
    // 4,4,4,1 → 3.25 → 3.3
    expect(shown.averageRating).toBe(3.3);
  });

  it("rejects invalid scores and reviewer ids; 404 for unknown talk", async () => {
    const speaker = await createSpeaker();
    const talk = await createTalk({ speakerId: speaker.id });
    const rid = uniqueReviewerId();
    expectStatus(await putRatingRaw(talk.id, rid, { score: 0 }), 400);
    expectStatus(await putRatingRaw(talk.id, rid, { score: 6 }), 400);
    expectStatus(await putRatingRaw(talk.id, rid, { score: 3.5 }), 400);
    expectStatus(await putRatingRaw(talk.id, rid, { score: "3" }), 400);
    expectStatus(await putRatingRaw(talk.id, 0, { score: 3 }), 400);
    expectStatus(await putRatingRaw(talk.id, -1, { score: 3 }), 400);
    expectStatus(await putRatingRaw(987654321, rid, { score: 3 }), 404);
  });

  it("ranks with sort=rating and rejects other sort values", async () => {
    const speaker = await createSpeaker();
    const low = await createTalk({ speakerId: speaker.id, title: "Low" });
    const high = await createTalk({ speakerId: speaker.id, title: "High" });
    const mid = await createTalk({ speakerId: speaker.id, title: "Mid" });
    await rateTalk(low.id, 2);
    await rateTalk(high.id, 5);
    await rateTalk(mid.id, 4);

    const ranked = await listTalks({ speakerId: speaker.id, sort: "rating" });
    expect(ranked.map((t) => t.id)).toEqual([high.id, mid.id, low.id]);

    expectStatus(await get("/talks?sort=title"), 400);
  });

  it("puts unrated talks last when sorting by rating", async () => {
    const speaker = await createSpeaker();
    const rated = await createTalk({ speakerId: speaker.id, title: "Rated" });
    const unrated = await createTalk({ speakerId: speaker.id, title: "Unrated" });
    await rateTalk(rated.id, 3);
    const ranked = await listTalks({ speakerId: speaker.id, sort: "rating" });
    expect(ranked.map((t) => t.id)).toEqual([rated.id, unrated.id]);
  });

  it("breaks rating ties by count then age (oldest first)", async () => {
    // Max 3 talks per speaker — keep this graph within the limit.
    const speaker = await createSpeaker();
    const fourOnce = await createTalk({ speakerId: speaker.id, title: "four-once" });
    const fourTwice = await createTalk({ speakerId: speaker.id, title: "four-twice" });
    const fourTwiceLater = await createTalk({ speakerId: speaker.id, title: "four-twice-later" });

    await rateTalk(fourTwice.id, 4);
    await rateTalk(fourTwice.id, 4);
    await rateTalk(fourOnce.id, 4);
    await rateTalk(fourTwiceLater.id, 4);
    await rateTalk(fourTwiceLater.id, 4);

    const ranked = await listTalks({ speakerId: speaker.id, sort: "rating" });
    // Same average 4.0: more ratings first; equal count → lower id (older) first.
    expect(ranked.map((t) => t.id)).toEqual([fourTwice.id, fourTwiceLater.id, fourOnce.id]);
  });

  it("shows null average and zero count wherever an unrated talk appears", async () => {
    const speaker = await createSpeaker();
    const talk = await createTalk({ speakerId: speaker.id, title: "No ratings yet" });
    const views = [
      await getTalk(talk.id),
      (await listTalks({ speakerId: speaker.id })).find((t) => t.id === talk.id),
      (await listSpeakerTalks(speaker.id)).find((t) => t.id === talk.id),
    ];
    for (const t of views) {
      expect(t).toBeDefined();
      expect(t!.averageRating).toBeNull();
      expect(t!.ratingCount).toBe(0);
    }
  });

  it("keeps concurrent ratings without losing or double-counting", async () => {
    const speaker = await createSpeaker();
    const talk = await createTalk({ speakerId: speaker.id });
    const reviewerId = uniqueReviewerId();
    const results = await Promise.all(
      Array.from({ length: 20 }, () => putRatingRaw(talk.id, reviewerId, { score: 4 })),
    );
    for (const res of results) {
      expect(res.status).toBe(200);
    }
    const shown = await getTalk(talk.id);
    expect(shown.ratingCount).toBe(1);
    expect(shown.averageRating).toBe(4);
  });

  it("can read averages on the shared baseline talk", async () => {
    const talk = await getTalk(baseline.talk.id);
    expect(talk.ratingCount).toBeGreaterThanOrEqual(1);
    expect(talk.averageRating).not.toBeNull();
  });
});
