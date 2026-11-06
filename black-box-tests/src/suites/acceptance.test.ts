// Acceptance state machine: specs/acceptance/spec.md. Test names carry the rule (R) or decision (D) they prove.
import { beforeAll, describe, expect, it } from "vitest";
import { expectStatus } from "../helpers/expect.js";
import {
  addCoSpeaker,
  createAcceptableTalk,
  createSpeaker,
  createTalk,
  decideTalk,
  decideTalkRaw,
  getTalk,
  listSpeakerTalks,
  listTalks,
  postTalkRaw,
  rateTalk,
  removeCoSpeaker,
  withdrawTalk,
  withdrawTalkRaw,
} from "../helpers/factories.js";
import { get, waitForHealth } from "../helpers/http.js";
import type { HttpResponse } from "../helpers/http.js";
import { uniqueReviewerId } from "../helpers/ids.js";
import type { Decision, Talk, TalkStatus } from "../helpers/types.js";

const UNKNOWN_ID = 999_999_999;

/** A refused request (R8): the given status code, and the talk's status is unchanged. */
async function expectRefused(res: HttpResponse<unknown>, code: number, talkId: number, unchanged: TalkStatus) {
  expectStatus(res, code);
  expect((await getTalk(talkId)).status).toBe(unchanged);
}

/** A talk of a fresh main speaker, brought to the given status. */
async function talkIn(status: TalkStatus): Promise<{ talk: Talk; speakerId: number }> {
  const speaker = await createSpeaker();
  const talk = await createAcceptableTalk(speaker.id);
  if (status === "SUBMITTED") return { talk, speakerId: speaker.id };
  if (status === "WITHDRAWN") return { talk: await withdrawTalk(talk.id, speaker.id), speakerId: speaker.id };
  return { talk: await decideTalk(talk.id, status), speakerId: speaker.id };
}

describe("acceptance", () => {
  beforeAll(async () => {
    await waitForHealth();
  });

  describe("state table (R2, R4, R6, D4, D6, D7, D11)", () => {
    // [from, request, expected code, status afterwards]. Every talk has two reviewers, so ratings never block.
    const table: Array<[TalkStatus, Decision | "withdraw", number, TalkStatus]> = [
      ["SUBMITTED", "ACCEPTED", 200, "ACCEPTED"],
      ["SUBMITTED", "REJECTED", 200, "REJECTED"],
      ["SUBMITTED", "WAITLISTED", 200, "WAITLISTED"],
      ["SUBMITTED", "withdraw", 200, "WITHDRAWN"],
      ["WAITLISTED", "ACCEPTED", 200, "ACCEPTED"],
      ["WAITLISTED", "REJECTED", 200, "REJECTED"],
      ["WAITLISTED", "WAITLISTED", 200, "WAITLISTED"], // D6
      ["WAITLISTED", "withdraw", 200, "WITHDRAWN"],
      ["ACCEPTED", "ACCEPTED", 200, "ACCEPTED"], // D7
      ["ACCEPTED", "REJECTED", 409, "ACCEPTED"],
      ["ACCEPTED", "WAITLISTED", 409, "ACCEPTED"],
      ["ACCEPTED", "withdraw", 200, "WITHDRAWN"],
      ["REJECTED", "ACCEPTED", 409, "REJECTED"],
      ["REJECTED", "REJECTED", 200, "REJECTED"], // D7
      ["REJECTED", "WAITLISTED", 409, "REJECTED"],
      ["REJECTED", "withdraw", 409, "REJECTED"], // D11
      ["WITHDRAWN", "ACCEPTED", 409, "WITHDRAWN"], // R6
      ["WITHDRAWN", "REJECTED", 409, "WITHDRAWN"],
      ["WITHDRAWN", "WAITLISTED", 409, "WITHDRAWN"],
      ["WITHDRAWN", "withdraw", 409, "WITHDRAWN"], // D11
    ];

    it.each(table)("%s + %s -> %i, then %s", async (from, request, code, after) => {
      const { talk, speakerId } = await talkIn(from);
      const res =
        request === "withdraw"
          ? await withdrawTalkRaw(talk.id, { speakerId })
          : await decideTalkRaw(talk.id, { status: request });
      expectStatus(res, code);
      if (code === 200) {
        // R7: the decision/withdraw response itself shows the status
        const body = res.json as Talk;
        expect(body.id).toBe(talk.id);
        expect(body.status).toBe(after);
      }
      expect((await getTalk(talk.id)).status).toBe(after); // R8 for the refusals
    });
  });

  describe("R3, D5: accepting needs two different reviewers", () => {
    it("refuses with 409 at 0 and 1 reviewers, and when one reviewer rates twice", async () => {
      const speaker = await createSpeaker();
      const talk = await createTalk({ speakerId: speaker.id });
      await expectRefused(await decideTalkRaw(talk.id, { status: "ACCEPTED" }), 409, talk.id, "SUBMITTED");

      const reviewer = uniqueReviewerId();
      await rateTalk(talk.id, 5, reviewer);
      await expectRefused(await decideTalkRaw(talk.id, { status: "ACCEPTED" }), 409, talk.id, "SUBMITTED");
      await rateTalk(talk.id, 2, reviewer);
      await expectRefused(await decideTalkRaw(talk.id, { status: "ACCEPTED" }), 409, talk.id, "SUBMITTED");

      await rateTalk(talk.id, 4);
      expect((await decideTalk(talk.id, "ACCEPTED")).status).toBe("ACCEPTED");
    });

    it("also applies to a waitlisted talk", async () => {
      const speaker = await createSpeaker();
      const talk = await createTalk({ speakerId: speaker.id });
      await decideTalk(talk.id, "WAITLISTED");
      await rateTalk(talk.id, 5);
      await expectRefused(await decideTalkRaw(talk.id, { status: "ACCEPTED" }), 409, talk.id, "WAITLISTED");
    });

    it("rejecting and waitlisting need no ratings", async () => {
      const speaker = await createSpeaker();
      const a = await createTalk({ speakerId: speaker.id });
      const b = await createTalk({ speakerId: speaker.id });
      expect((await decideTalk(a.id, "REJECTED")).status).toBe("REJECTED");
      expect((await decideTalk(b.id, "WAITLISTED")).status).toBe("WAITLISTED");
    });
  });

  describe("R5, D9, D10: only the main speaker can withdraw (403)", () => {
    it("refuses another speaker, a co-speaker and an unregistered id", async () => {
      const main = await createSpeaker();
      const co = await createSpeaker();
      const stranger = await createSpeaker();
      const talk = await createTalk({ speakerId: main.id });
      await addCoSpeaker(talk.id, co.id);

      await expectRefused(await withdrawTalkRaw(talk.id, { speakerId: stranger.id }), 403, talk.id, "SUBMITTED");
      await expectRefused(await withdrawTalkRaw(talk.id, { speakerId: co.id }), 403, talk.id, "SUBMITTED");
      await expectRefused(await withdrawTalkRaw(talk.id, { speakerId: UNKNOWN_ID }), 403, talk.id, "SUBMITTED");
    });

    it("D11, D12: a stranger gets 403, not 409, on a rejected or withdrawn talk", async () => {
      const rejected = await talkIn("REJECTED");
      const withdrawn = await talkIn("WITHDRAWN");
      const stranger = await createSpeaker();
      await expectRefused(await withdrawTalkRaw(rejected.talk.id, { speakerId: stranger.id }), 403, rejected.talk.id, "REJECTED");
      await expectRefused(await withdrawTalkRaw(withdrawn.talk.id, { speakerId: stranger.id }), 403, withdrawn.talk.id, "WITHDRAWN");
      await expectRefused(await withdrawTalkRaw(withdrawn.talk.id, { speakerId: UNKNOWN_ID }), 403, withdrawn.talk.id, "WITHDRAWN");
    });
  });

  describe("invalid requests", () => {
    it("D1: unknown talk is 404", async () => {
      const speaker = await createSpeaker();
      expectStatus(await decideTalkRaw(UNKNOWN_ID, { status: "REJECTED" }), 404);
      expectStatus(await withdrawTalkRaw(UNKNOWN_ID, { speakerId: speaker.id }), 404);
    });

    it("D12: unknown talk is 404 even with a bad body", async () => {
      expectStatus(await decideTalkRaw(UNKNOWN_ID, { status: "nope" }), 404);
      expectStatus(await decideTalkRaw(UNKNOWN_ID, "not json"), 404);
      expectStatus(await decideTalkRaw(UNKNOWN_ID), 404);
      expectStatus(await withdrawTalkRaw(UNKNOWN_ID, {}), 404);
      expectStatus(await withdrawTalkRaw(UNKNOWN_ID, "not json"), 404);
    });

    it.each<[string, unknown]>([
      ["missing", {}],
      ["null", { status: null }],
      ["a number", { status: 1 }],
      ["a boolean", { status: true }],
      ["an array", { status: ["ACCEPTED"] }],
      ["unknown", { status: "MAYBE" }],
      ["lowercase", { status: "accepted" }],
      ["mixed case", { status: "Accepted" }],
      ["padded", { status: " ACCEPTED" }],
      ["SUBMITTED", { status: "SUBMITTED" }],
      ["WITHDRAWN", { status: "WITHDRAWN" }],
    ])("D2: status %s is 400", async (_label, body) => {
      const { talk } = await talkIn("SUBMITTED");
      await expectRefused(await decideTalkRaw(talk.id, body), 400, talk.id, "SUBMITTED");
    });

    it("D2, D12: a bad status on a final talk is 400, not 409", async () => {
      const { talk } = await talkIn("REJECTED");
      await expectRefused(await decideTalkRaw(talk.id, { status: "SUBMITTED" }), 400, talk.id, "REJECTED");
    });

    it.each<[string, unknown]>([
      ["no body", undefined],
      ["not JSON", "not json"],
      ["a JSON array", ["ACCEPTED"]],
      ["a JSON string", '"ACCEPTED"'],
    ])("D3: decision with %s is 400", async (_label, body) => {
      const { talk } = await talkIn("SUBMITTED");
      await expectRefused(await decideTalkRaw(talk.id, body), 400, talk.id, "SUBMITTED");
    });

    it.each<[string, unknown]>([
      ["no body", undefined],
      ["not JSON", "not json"],
    ])("D3: withdrawal with %s is 400", async (_label, body) => {
      const { talk } = await talkIn("SUBMITTED");
      await expectRefused(await withdrawTalkRaw(talk.id, body), 400, talk.id, "SUBMITTED");
    });

    it("D8: speakerId must be a whole number (400)", async () => {
      const { talk, speakerId } = await talkIn("SUBMITTED");
      const bad: unknown[] = [
        {},
        { speakerId: null },
        { speakerId: String(speakerId) },
        `{"speakerId":${speakerId}.5}`,
        { speakerId: true },
        { speakerId: [speakerId] },
      ];
      for (const body of bad) {
        expectStatus(await withdrawTalkRaw(talk.id, body), 400);
      }
      expect((await getTalk(talk.id)).status).toBe("SUBMITTED");
    });

    it("D12: a bad body beats 403 and 409", async () => {
      const { talk } = await talkIn("WITHDRAWN");
      await expectRefused(await withdrawTalkRaw(talk.id, {}), 400, talk.id, "WITHDRAWN");
      await expectRefused(await decideTalkRaw(talk.id, { status: "nope" }), 400, talk.id, "WITHDRAWN");
    });
  });

  it("R7: the status shows wherever the talk is shown", async () => {
    const main = await createSpeaker();
    const co = await createSpeaker();
    const talk = await createTalk({ speakerId: main.id });
    await decideTalk(talk.id, "WAITLISTED");

    const find = (list: Talk[]) => list.find((t) => t.id === talk.id)?.status;
    expect((await getTalk(talk.id)).status).toBe("WAITLISTED");
    expect(find(await listTalks())).toBe("WAITLISTED");
    expect(find(await listTalks({ speakerId: main.id }))).toBe("WAITLISTED");
    expect(find(await listTalks({ sort: "rating" }))).toBe("WAITLISTED");
    expect(find(await listSpeakerTalks(main.id))).toBe("WAITLISTED");
    expect((await addCoSpeaker(talk.id, co.id)).status).toBe("WAITLISTED");
    expect((await removeCoSpeaker(talk.id, co.id)).status).toBe("WAITLISTED");

    await withdrawTalk(talk.id, main.id);
    expect(find(await listSpeakerTalks(main.id))).toBe("WITHDRAWN");
  });

  it("D13: withdrawn and rejected talks still count towards the 3-talk limit", async () => {
    const speaker = await createSpeaker();
    const a = await createTalk({ speakerId: speaker.id });
    const b = await createTalk({ speakerId: speaker.id });
    await createTalk({ speakerId: speaker.id });
    await withdrawTalk(a.id, speaker.id);
    await decideTalk(b.id, "REJECTED");
    expectStatus(
      await postTalkRaw({ title: "fourth", format: "PRESENTATION", level: "BEGINNER", speakerId: speaker.id }),
      409,
    );
  });

  it("D14: /admin/stats counts the new statuses", async () => {
    await talkIn("REJECTED");
    await talkIn("WITHDRAWN");
    const res = await get<{ byStatus: Record<string, number> }>("/admin/stats");
    expectStatus(res, 200);
    expect(res.json.byStatus.REJECTED).toBeGreaterThanOrEqual(1);
    expect(res.json.byStatus.WITHDRAWN).toBeGreaterThanOrEqual(1);
  });

  it("ratings keep working on a withdrawn talk", async () => {
    const { talk } = await talkIn("WITHDRAWN");
    await rateTalk(talk.id, 3);
    expect((await getTalk(talk.id)).ratingCount).toBe(3);
  });

  describe("R9: concurrent decisions and withdrawals", () => {
    it("REJECTED and ACCEPTED at once on a waitlisted talk: exactly one decision wins", async () => {
      for (let round = 0; round < 5; round++) {
        const { talk } = await talkIn("WAITLISTED");
        const results = await Promise.all(
          Array.from({ length: 8 }, (_, i) =>
            decideTalkRaw(talk.id, { status: i % 2 === 0 ? "REJECTED" : "ACCEPTED" }),
          ),
        );
        for (const res of results) expect([200, 409]).toContain(res.status);
        // Repeating the winning decision is 200 (D7); the other decision is never 200.
        const winners = new Set(results.filter((r) => r.status === 200).map((r) => (r.json as Talk).status));
        expect(winners.size).toBe(1);
        expect((await getTalk(talk.id)).status).toBe([...winners][0]);
      }
    });

    it("withdraw and REJECTED at once: never both, withdrawn at most once", async () => {
      for (let round = 0; round < 5; round++) {
        const { talk, speakerId } = await talkIn("SUBMITTED");
        const results = await Promise.all(
          Array.from({ length: 6 }, (_, i) =>
            i % 2 === 0 ? decideTalkRaw(talk.id, { status: "REJECTED" }) : withdrawTalkRaw(talk.id, { speakerId }),
          ),
        );
        for (const res of results) expect([200, 409]).toContain(res.status);
        const ok = results.filter((r) => r.status === 200).map((r) => (r.json as Talk).status);
        const withdrawals = ok.filter((s) => s === "WITHDRAWN").length;
        const rejections = ok.filter((s) => s === "REJECTED").length;
        expect(withdrawals === 0 || rejections === 0, `${withdrawals} withdrawals, ${rejections} rejections`).toBe(true);
        expect(withdrawals).toBeLessThanOrEqual(1);
        expect((await getTalk(talk.id)).status).toBe(withdrawals === 1 ? "WITHDRAWN" : "REJECTED");
      }
    });
  });
});
