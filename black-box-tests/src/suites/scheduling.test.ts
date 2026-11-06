// Programme scheduling: specs/scheduling/spec.md. Test names carry the rule (R) or decision (D) they prove.
import { beforeAll, describe, expect, it } from "vitest";
import { expectStatus } from "../helpers/expect.js";
import {
  addCoSpeaker,
  clearProgrammeCell,
  createAcceptedTalk,
  createAcceptableTalk,
  createSpeaker,
  createTalk,
  decideTalk,
  deleteProgramme,
  deleteProgrammeRaw,
  getProgramme,
  getProgrammeRaw,
  getTalk,
  putProgramme,
  putProgrammeRaw,
  withdrawTalk,
} from "../helpers/factories.js";
import { waitForHealth } from "../helpers/http.js";
import type { ProgrammeEntry, Room, Slot, TalkStatus } from "../helpers/types.js";
import { SLOT_TIMES } from "../helpers/types.js";

const UNKNOWN_ID = 999_999_999;

function assertEntry(e: ProgrammeEntry, room: Room, slot: Slot, talkId: number) {
  expect(e.room).toBe(room);
  expect(e.slot).toBe(slot);
  expect(e.start).toBe(SLOT_TIMES[slot].start);
  expect(e.end).toBe(SLOT_TIMES[slot].end);
  expect(e.talkId).toBe(talkId);
}

/** Clear fixed cells this test will touch (shared venue; no truncate). */
async function clear(...cells: Array<[Room, Slot]>) {
  for (const [room, slot] of cells) await clearProgrammeCell(room, slot);
}

describe("scheduling", () => {
  beforeAll(async () => {
    await waitForHealth();
  });

  describe("R1: fixed venue times", () => {
    it("returns HH:mm start/end from the venue table for every slot", async () => {
      const slots = Object.keys(SLOT_TIMES) as Slot[];
      for (const slot of slots) {
        await clear(["R1", slot]);
        // Fresh speaker per slot — a speaker may submit at most 3 talks
        const talk = await createAcceptedTalk((await createSpeaker()).id);
        const entry = await putProgramme("R1", slot, talk.id);
        assertEntry(entry, "R1", slot, talk.id);
        await deleteProgramme("R1", slot);
      }
    });
  });

  describe("R2, D2, D11, D12, D16: put / get / delete", () => {
    it("places, reads, removes (with Entry), and allows re-place elsewhere", async () => {
      await clear(["R1", "S1"], ["R2", "S1"]);
      const speaker = await createSpeaker();
      const talk = await createAcceptedTalk(speaker.id);

      const placed = await putProgramme("R1", "S1", talk.id);
      assertEntry(placed, "R1", "S1", talk.id);
      assertEntry(await getProgramme("R1", "S1"), "R1", "S1", talk.id);

      const removed = await deleteProgramme("R1", "S1");
      assertEntry(removed, "R1", "S1", talk.id);
      expectStatus(await getProgrammeRaw("R1", "S1"), 404);
      expectStatus(await deleteProgrammeRaw("R1", "S1"), 404);

      assertEntry(await putProgramme("R2", "S1", talk.id), "R2", "S1", talk.id);
      await deleteProgramme("R2", "S1");
    });

    it("accepts trailing slash on put/get/delete (D16)", async () => {
      await clear(["R3", "S2"]);
      const speaker = await createSpeaker();
      const talk = await createAcceptedTalk(speaker.id);

      const putRes = await putProgrammeRaw("R3", "S2", { talkId: talk.id }, true);
      expectStatus(putRes, 200);
      assertEntry(putRes.json as ProgrammeEntry, "R3", "S2", talk.id);

      const getRes = await getProgrammeRaw("R3", "S2", true);
      expectStatus(getRes, 200);

      const delRes = await deleteProgrammeRaw("R3", "S2", true);
      expectStatus(delRes, 200);
      assertEntry(delRes.json as ProgrammeEntry, "R3", "S2", talk.id);
    });
  });

  describe("D1, D3, D4, D13: path / body / talk / order", () => {
    it("unknown or wrong-case room/slot is 404 (D1)", async () => {
      const speaker = await createSpeaker();
      const talk = await createAcceptedTalk(speaker.id);
      for (const [room, slot] of [
        ["R9", "S1"],
        ["R1", "S9"],
        ["r1", "S1"],
        ["R1", "s1"],
      ] as const) {
        expectStatus(await putProgrammeRaw(room, slot, { talkId: talk.id }), 404);
        expectStatus(await getProgrammeRaw(room, slot), 404);
        expectStatus(await deleteProgrammeRaw(room, slot), 404);
      }
    });

    it("bad put body is 400 (D3)", async () => {
      await clear(["R1", "S3"]);
      expectStatus(await putProgrammeRaw("R1", "S3", undefined), 400);
      expectStatus(await putProgrammeRaw("R1", "S3", {}), 400);
      expectStatus(await putProgrammeRaw("R1", "S3", { talkId: null }), 400);
      expectStatus(await putProgrammeRaw("R1", "S3", { talkId: "1" }), 400);
      expectStatus(await putProgrammeRaw("R1", "S3", { talkId: 1.5 }), 400);
    });

    it("unknown talkId is 404 (D4)", async () => {
      await clear(["R2", "S3"]);
      expectStatus(await putProgrammeRaw("R2", "S3", { talkId: UNKNOWN_ID }), 404);
    });

    it("unknown room beats bad body (D13)", async () => {
      expectStatus(await putProgrammeRaw("R9", "S1", undefined), 404);
    });
  });

  describe("R3, D5: only ACCEPTED talks", () => {
    it.each([
      ["SUBMITTED", async (id: number) => createTalk({ speakerId: id })],
      ["WAITLISTED", async (id: number) => decideTalk((await createTalk({ speakerId: id })).id, "WAITLISTED")],
      ["REJECTED", async (id: number) => decideTalk((await createTalk({ speakerId: id })).id, "REJECTED")],
      [
        "WITHDRAWN",
        async (id: number) => withdrawTalk((await createTalk({ speakerId: id })).id, id),
      ],
    ] as Array<[TalkStatus, (speakerId: number) => Promise<{ id: number }>]>)(
      "%s talk is 409",
      async (_status, make) => {
        await clear(["R1", "S5"]);
        const speaker = await createSpeaker();
        const talk = await make(speaker.id);
        expectStatus(await putProgrammeRaw("R1", "S5", { talkId: talk.id }), 409);
        expectStatus(await getProgrammeRaw("R1", "S5"), 404);
      },
    );
  });

  describe("R4, D6: workshop fit", () => {
    it("workshop only fits S4; other formats fit any slot including S4", async () => {
      await clear(["R2", "S1"], ["R2", "S4"], ["R3", "S4"]);
      // Separate speakers — same slot different rooms still enforces R6
      const workshop = await createAcceptedTalk((await createSpeaker()).id, { format: "WORKSHOP" });
      const presentation = await createAcceptedTalk((await createSpeaker()).id, {
        format: "PRESENTATION",
      });

      expectStatus(await putProgrammeRaw("R2", "S1", { talkId: workshop.id }), 409);
      assertEntry(await putProgramme("R2", "S4", workshop.id), "R2", "S4", workshop.id);
      assertEntry(await putProgramme("R3", "S4", presentation.id), "R3", "S4", presentation.id);

      await deleteProgramme("R2", "S4");
      await deleteProgramme("R3", "S4");
    });
  });

  describe("R5, D7, D8, D9: occupancy and once-only", () => {
    it("second talk in same cell is 409; same talk same cell is 200; elsewhere needs DELETE", async () => {
      await clear(["R1", "S2"], ["R2", "S2"]);
      const aSpeaker = await createSpeaker();
      const bSpeaker = await createSpeaker();
      const a = await createAcceptedTalk(aSpeaker.id);
      const b = await createAcceptedTalk(bSpeaker.id);

      await putProgramme("R1", "S2", a.id);
      expectStatus(await putProgrammeRaw("R1", "S2", { talkId: b.id }), 409);
      assertEntry(await putProgramme("R1", "S2", a.id), "R1", "S2", a.id); // D9

      expectStatus(await putProgrammeRaw("R2", "S2", { talkId: a.id }), 409); // D8
      await deleteProgramme("R1", "S2");
      assertEntry(await putProgramme("R2", "S2", a.id), "R2", "S2", a.id);
      await deleteProgramme("R2", "S2");
    });
  });

  describe("R6, D10: speaker / co-speaker clash in a slot", () => {
    it("same main speaker in same slot different rooms is 409", async () => {
      await clear(["R1", "S3"], ["R2", "S3"]);
      const speaker = await createSpeaker();
      const other = await createSpeaker();
      const a = await createAcceptedTalk(speaker.id);
      const b = await createAcceptedTalk(speaker.id);
      const c = await createAcceptedTalk(other.id);

      await putProgramme("R1", "S3", a.id);
      expectStatus(await putProgrammeRaw("R2", "S3", { talkId: b.id }), 409);
      assertEntry(await putProgramme("R2", "S3", c.id), "R2", "S3", c.id);

      await deleteProgramme("R1", "S3");
      await deleteProgramme("R2", "S3");
    });

    it("co-speaker clash in the same slot is 409", async () => {
      await clear(["R1", "S5"], ["R3", "S5"]);
      const mainA = await createSpeaker();
      const mainB = await createSpeaker();
      const co = await createSpeaker();
      const talkA = await createAcceptedTalk(mainA.id);
      await addCoSpeaker(talkA.id, co.id);
      const talkB = await createAcceptedTalk(mainB.id);
      // talkB's main is co of talkA — conflict when placed same slot
      const talkClash = await createAcceptedTalk(co.id);

      await putProgramme("R1", "S5", talkA.id);
      expectStatus(await putProgrammeRaw("R3", "S5", { talkId: talkClash.id }), 409);
      assertEntry(await putProgramme("R3", "S5", talkB.id), "R3", "S5", talkB.id);

      await deleteProgramme("R1", "S5");
      await deleteProgramme("R3", "S5");
    });
  });

  describe("R7, D9, D14, D15: later talk changes leave the programme", () => {
    it("withdraw after schedule leaves the Entry; re-PUT same cell stays 200 (D9+R7)", async () => {
      await clear(["R3", "S1"]);
      const speaker = await createSpeaker();
      const talk = await createAcceptedTalk(speaker.id);
      await putProgramme("R3", "S1", talk.id);
      await withdrawTalk(talk.id, speaker.id);
      expect((await getTalk(talk.id)).status).toBe("WITHDRAWN");
      assertEntry(await getProgramme("R3", "S1"), "R3", "S1", talk.id);
      assertEntry(await putProgramme("R3", "S1", talk.id), "R3", "S1", talk.id);
      await deleteProgramme("R3", "S1");
    });

    it("co-speaker added after schedule does not re-check clash (D15)", async () => {
      await clear(["R1", "S4"], ["R2", "S4"]);
      const mainA = await createSpeaker();
      const mainB = await createSpeaker();
      const laterCo = await createSpeaker();
      const a = await createAcceptedTalk(mainA.id);
      const b = await createAcceptedTalk(mainB.id);

      await putProgramme("R1", "S4", a.id);
      await putProgramme("R2", "S4", b.id);
      // Adding mainB as co of a would clash if re-checked — programme stays
      await addCoSpeaker(a.id, mainB.id);
      assertEntry(await getProgramme("R1", "S4"), "R1", "S4", a.id);
      assertEntry(await getProgramme("R2", "S4"), "R2", "S4", b.id);
      // Fresh put of a third talk that shares speakers still checked at put time
      await clear(["R3", "S4"]);
      const clash = await createAcceptedTalk(laterCo.id);
      await addCoSpeaker(clash.id, mainA.id);
      expectStatus(await putProgrammeRaw("R3", "S4", { talkId: clash.id }), 409);

      await deleteProgramme("R1", "S4");
      await deleteProgramme("R2", "S4");
    });
  });

  describe("D13 stacked conflicts", () => {
    it("status conflict beats cell occupied", async () => {
      await clear(["R2", "S5"]);
      const aSpeaker = await createSpeaker();
      const bSpeaker = await createSpeaker();
      const a = await createAcceptedTalk(aSpeaker.id);
      const submitted = await createTalk({ speakerId: bSpeaker.id });
      await putProgramme("R2", "S5", a.id);
      expectStatus(await putProgrammeRaw("R2", "S5", { talkId: submitted.id }), 409);
      assertEntry(await getProgramme("R2", "S5"), "R2", "S5", a.id);
      await deleteProgramme("R2", "S5");
    });

    it("talk already elsewhere beats cell occupied", async () => {
      await clear(["R1", "S1"], ["R3", "S1"]);
      const aSpeaker = await createSpeaker();
      const bSpeaker = await createSpeaker();
      const a = await createAcceptedTalk(aSpeaker.id);
      const b = await createAcceptedTalk(bSpeaker.id);
      await putProgramme("R1", "S1", a.id);
      await putProgramme("R3", "S1", b.id);
      // a already elsewhere; cell R3/S1 occupied — D8 before D7
      expectStatus(await putProgrammeRaw("R3", "S1", { talkId: a.id }), 409);
      assertEntry(await getProgramme("R1", "S1"), "R1", "S1", a.id);
      assertEntry(await getProgramme("R3", "S1"), "R3", "S1", b.id);
      await deleteProgramme("R1", "S1");
      await deleteProgramme("R3", "S1");
    });
  });

  describe("R8: concurrent programme edits", () => {
    it("concurrent puts into the same cell: exactly one wins", async () => {
      await clear(["R3", "S3"]);
      const s1 = await createSpeaker();
      const s2 = await createSpeaker();
      const a = await createAcceptedTalk(s1.id);
      const b = await createAcceptedTalk(s2.id);

      const [r1, r2] = await Promise.all([
        putProgrammeRaw("R3", "S3", { talkId: a.id }),
        putProgrammeRaw("R3", "S3", { talkId: b.id }),
      ]);
      const codes = [r1.status, r2.status].sort();
      expect(codes).toEqual([200, 409]);
      const kept = await getProgramme("R3", "S3");
      expect([a.id, b.id]).toContain(kept.talkId);
      await deleteProgramme("R3", "S3");
    });

    it("concurrent same-speaker across rooms: never both placed", async () => {
      await clear(["R1", "S2"], ["R2", "S2"]);
      const speaker = await createSpeaker();
      const a = await createAcceptedTalk(speaker.id);
      const b = await createAcceptedTalk(speaker.id);

      const [r1, r2] = await Promise.all([
        putProgrammeRaw("R1", "S2", { talkId: a.id }),
        putProgrammeRaw("R2", "S2", { talkId: b.id }),
      ]);
      const oks = [r1, r2].filter((r) => r.status === 200);
      const conflicts = [r1, r2].filter((r) => r.status === 409);
      expect(oks.length).toBe(1);
      expect(conflicts.length).toBe(1);

      const g1 = await getProgrammeRaw("R1", "S2");
      const g2 = await getProgrammeRaw("R2", "S2");
      const placed = [g1, g2].filter((g) => g.status === 200);
      expect(placed.length).toBe(1);

      await clear(["R1", "S2"], ["R2", "S2"]);
    });
  });

  describe("prerequisite helpers", () => {
    it("createAcceptableTalk still works without accepting", async () => {
      const speaker = await createSpeaker();
      const talk = await createAcceptableTalk(speaker.id);
      expect(talk.status).toBe("SUBMITTED");
      expect(talk.ratingCount).toBe(2);
    });
  });
});
