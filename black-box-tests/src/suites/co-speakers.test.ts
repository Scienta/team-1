import { beforeAll, describe, expect, it } from "vitest";
import { expectStatus } from "../helpers/expect.js";
import {
  addCoSpeaker,
  createSpeaker,
  createTalk,
  deleteCoSpeakerRaw,
  getTalk,
  listSpeakerTalks,
  listTalks,
  postTalkRaw,
  putCoSpeakerRaw,
  removeCoSpeaker,
} from "../helpers/factories.js";
import { waitForHealth } from "../helpers/http.js";

describe("co-speakers", () => {
  beforeAll(async () => {
    await waitForHealth();
  });

  it("starts empty, keeps add order, is idempotent, and caps at two", async () => {
    const main = await createSpeaker();
    const a = await createSpeaker();
    const b = await createSpeaker();
    const c = await createSpeaker();
    const talk = await createTalk({ speakerId: main.id });

    expect((await getTalk(talk.id)).coSpeakerIds ?? []).toEqual([]);

    expect((await addCoSpeaker(talk.id, b.id)).coSpeakerIds).toEqual([b.id]);
    expect((await addCoSpeaker(talk.id, a.id)).coSpeakerIds).toEqual([b.id, a.id]);
    // Re-adding b changes nothing
    expect((await addCoSpeaker(talk.id, b.id)).coSpeakerIds).toEqual([b.id, a.id]);
    expectStatus(await putCoSpeakerRaw(talk.id, c.id), 409);
    expect((await getTalk(talk.id)).coSpeakerIds).toEqual([b.id, a.id]);

    expect((await removeCoSpeaker(talk.id, b.id)).coSpeakerIds).toEqual([a.id]);
    expect((await addCoSpeaker(talk.id, c.id)).coSpeakerIds).toEqual([a.id, c.id]);
  });

  it("rejects the main speaker as their own co-speaker with 400", async () => {
    const main = await createSpeaker();
    const co = await createSpeaker();
    const talk = await createTalk({ speakerId: main.id });
    await addCoSpeaker(talk.id, co.id);

    expectStatus(await putCoSpeakerRaw(talk.id, main.id), 400);
    expect((await getTalk(talk.id)).coSpeakerIds).toEqual([co.id]);
  });

  it("returns 404 when removing someone who is not a co-speaker", async () => {
    const main = await createSpeaker();
    const a = await createSpeaker();
    const b = await createSpeaker();
    const stranger = await createSpeaker();
    const talk = await createTalk({ speakerId: main.id });
    await addCoSpeaker(talk.id, a.id);
    await addCoSpeaker(talk.id, b.id);

    expect((await removeCoSpeaker(talk.id, b.id)).coSpeakerIds).toEqual([a.id]);
    expectStatus(await deleteCoSpeakerRaw(talk.id, b.id), 404);
    expectStatus(await deleteCoSpeakerRaw(talk.id, stranger.id), 404);
    expectStatus(await deleteCoSpeakerRaw(talk.id, main.id), 404);
    expect((await getTalk(talk.id)).coSpeakerIds).toEqual([a.id]);
  });

  it("returns 404 for unknown talk or speaker", async () => {
    const main = await createSpeaker();
    const talk = await createTalk({ speakerId: main.id });
    expectStatus(await putCoSpeakerRaw(999999, main.id), 404);
    expectStatus(await deleteCoSpeakerRaw(999999, main.id), 404);
    expectStatus(await putCoSpeakerRaw(talk.id, 999999), 404);
    expectStatus(await deleteCoSpeakerRaw(talk.id, 999999), 404);
  });

  it("does not count co-speaking towards the main-speaker talk limit", async () => {
    const busy = await createSpeaker();
    await createTalk({ speakerId: busy.id });
    await createTalk({ speakerId: busy.id });
    await createTalk({ speakerId: busy.id });
    const other = await createSpeaker();
    const talk = await createTalk({ speakerId: other.id });

    await addCoSpeaker(talk.id, busy.id);
    expect(await listSpeakerTalks(busy.id)).toHaveLength(3);
    expect(await listTalks({ speakerId: busy.id })).toHaveLength(3);
  });

  it("still allows three own submissions after co-speaking on three talks", async () => {
    const speaker = await createSpeaker();
    for (let i = 0; i < 3; i++) {
      const host = await createSpeaker();
      const talk = await createTalk({ speakerId: host.id });
      await addCoSpeaker(talk.id, speaker.id);
    }
    await createTalk({ speakerId: speaker.id });
    await createTalk({ speakerId: speaker.id });
    await createTalk({ speakerId: speaker.id });
    expectStatus(
      await postTalkRaw({
        title: "fourth",
        format: "PRESENTATION",
        level: "BEGINNER",
        speakerId: speaker.id,
      }),
      409,
    );
    expect(await listSpeakerTalks(speaker.id)).toHaveLength(3);
  });

  it("shows coSpeakerIds on talk lists for the main speaker", async () => {
    const main = await createSpeaker();
    const co = await createSpeaker();
    const talk = await createTalk({ speakerId: main.id });
    await addCoSpeaker(talk.id, co.id);

    const listed = await listTalks({ speakerId: main.id });
    expect(listed).toHaveLength(1);
    expect(listed[0].coSpeakerIds).toEqual([co.id]);
    // Co-speaker's own list stays empty (they are not main)
    expect(await listSpeakerTalks(co.id)).toEqual([]);
  });

  it("never exceeds two co-speakers under concurrent adds", async () => {
    const main = await createSpeaker();
    const talk = await createTalk({ speakerId: main.id });
    const candidates = await Promise.all(Array.from({ length: 6 }, () => createSpeaker()));

    const results = await Promise.all(
      candidates.map((s) => putCoSpeakerRaw(talk.id, s.id)),
    );
    for (const res of results) {
      expect([200, 409]).toContain(res.status);
    }
    expect(results.filter((r) => r.status === 200)).toHaveLength(2);
    expect((await getTalk(talk.id)).coSpeakerIds).toHaveLength(2);
  });
});
