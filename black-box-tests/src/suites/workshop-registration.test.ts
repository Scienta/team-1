// Workshop registration with capacity: specs/workshop-registration/spec.md. Test names carry the rule (R) or
// decision (D) they prove.
import { beforeAll, describe, expect, it } from "vitest";
import { expectStatus } from "../helpers/expect.js";
import {
  addCoSpeaker,
  createAcceptedTalk,
  createAcceptedWorkshop,
  createSpeaker,
  createTalk,
  decideTalk,
  listRegistrations,
  register,
  registerRaw,
  withdrawTalk,
} from "../helpers/factories.js";
import { del, get, waitForHealth } from "../helpers/http.js";
import type { HttpResponse } from "../helpers/http.js";
import { uniqueEmail } from "../helpers/ids.js";

const UNKNOWN_ID = 999_999_999;
const CAPACITY = 20;

/** A refused request: the given status code, and the workshop still has `count` registrations. */
async function expectRefused(res: HttpResponse<unknown>, code: number, workshopId: number, count: number) {
  expectStatus(res, code);
  expect(await listRegistrations(workshopId)).toHaveLength(count);
}

async function fill(workshopId: number, n: number) {
  for (let i = 0; i < n; i++) await register(workshopId, `Attendee ${i}`, uniqueEmail("attendee"));
}

describe("workshop-registration", () => {
  beforeAll(async () => {
    await waitForHealth();
  });

  it("R1: registering returns 201 with id, workshopId, name and email", async () => {
    const w = await createAcceptedWorkshop();
    const email = uniqueEmail("ada");
    const r = await register(w.id, "Ada Lovelace", email);
    expect(Number.isInteger(r.id)).toBe(true);
    expect(r).toEqual({ id: r.id, workshopId: w.id, name: "Ada Lovelace", email });
    expect(await listRegistrations(w.id)).toEqual([r]);
  });

  it("R2, D2: a talk that isn't a workshop is 404 on POST and GET", async () => {
    const speaker = await createSpeaker();
    const presentation = await createAcceptedTalk(speaker.id, "PRESENTATION");
    const lightning = await createAcceptedTalk(speaker.id, "LIGHTNING_20");
    for (const t of [presentation, lightning]) {
      expectStatus(await registerRaw(t.id, { name: "Ada", email: uniqueEmail() }), 404);
      expectStatus(await get(`/workshops/${t.id}/registrations`), 404);
    }
  });

  it("R3, D8: a workshop that isn't ACCEPTED is 409 and stores nothing", async () => {
    const speaker = await createSpeaker();
    const submitted = await createTalk({ speakerId: speaker.id, format: "WORKSHOP" });
    const waitlisted = await decideTalk((await createTalk({ speakerId: speaker.id, format: "WORKSHOP" })).id, "WAITLISTED");
    const rejected = await decideTalk((await createTalk({ speakerId: speaker.id, format: "WORKSHOP" })).id, "REJECTED");
    const other = await createSpeaker();
    const withdrawn = await withdrawTalk((await createTalk({ speakerId: other.id, format: "WORKSHOP" })).id, other.id);
    for (const t of [submitted, waitlisted, rejected, withdrawn]) {
      await expectRefused(await registerRaw(t.id, { name: "Ada", email: uniqueEmail() }), 409, t.id, 0);
    }
  });

  it("R4, D15, D20: a withdrawn workshop keeps its list, takes no new registrations, and full stays 409", async () => {
    const speaker = await createSpeaker();
    const w = await createAcceptedTalk(speaker.id, "WORKSHOP");
    const first = await register(w.id, "Ada", uniqueEmail("ada"));
    await fill(w.id, CAPACITY - 1);
    await withdrawTalk(w.id, speaker.id);
    const list = await listRegistrations(w.id);
    expect(list).toHaveLength(CAPACITY);
    expect(list[0]).toEqual(first);
    await expectRefused(await registerRaw(w.id, { name: "Late", email: uniqueEmail() }), 409, w.id, CAPACITY);
  });

  it("D15: listing a workshop that isn't accepted is 200 []", async () => {
    const speaker = await createSpeaker();
    const submitted = await createTalk({ speakerId: speaker.id, format: "WORKSHOP" });
    expect(await listRegistrations(submitted.id)).toEqual([]);
  });

  it("R5, D10: the 21st registration is 409", async () => {
    const w = await createAcceptedWorkshop();
    await fill(w.id, CAPACITY);
    await expectRefused(await registerRaw(w.id, { name: "Number 21", email: uniqueEmail() }), 409, w.id, CAPACITY);
  });

  it("R5, D16: a parallel rush gets exactly 20 seats, listed in id order", async () => {
    const w = await createAcceptedWorkshop();
    const results = await Promise.all(
      Array.from({ length: 50 }, (_, i) => registerRaw(w.id, { name: `Rush ${i}`, email: uniqueEmail("rush") })),
    );
    for (const r of results) expect([201, 409], r.text).toContain(r.status);
    expect(results.filter((r) => r.status === 201)).toHaveLength(CAPACITY);
    const list = await listRegistrations(w.id);
    expect(list).toHaveLength(CAPACITY);
    const ids = list.map((r) => r.id);
    expect(ids).toEqual([...ids].sort((a, b) => a - b));
  });

  it("R6, D9, D12: the same email in any case or with surrounding whitespace is 409; the first keeps its place", async () => {
    const w = await createAcceptedWorkshop();
    const email = uniqueEmail("ada");
    const first = await register(w.id, "Ada", email);
    await register(w.id, "Grace", uniqueEmail("grace"));
    for (const again of [email, email.toUpperCase(), `  ${email}  `, `\t${email}\n`]) {
      await expectRefused(await registerRaw(w.id, { name: "Ada Again", email: again }), 409, w.id, 2);
    }
    expect((await listRegistrations(w.id))[0]).toEqual(first);
  });

  it("R6: parallel registrations with one email in different spellings get one seat", async () => {
    const w = await createAcceptedWorkshop();
    const email = uniqueEmail("dup");
    const spellings = [email, email.toUpperCase(), ` ${email}`, `${email} `];
    const results = await Promise.all(
      Array.from({ length: 16 }, (_, i) => registerRaw(w.id, { name: `Dup ${i}`, email: spellings[i % 4] })),
    );
    for (const r of results) expect([201, 409], r.text).toContain(r.status);
    expect(results.filter((r) => r.status === 201)).toHaveLength(1);
    expect(await listRegistrations(w.id)).toHaveLength(1);
  });

  it("D11: a duplicate on a full workshop is 409", async () => {
    const w = await createAcceptedWorkshop();
    const email = uniqueEmail("ada");
    await register(w.id, "Ada", email);
    await fill(w.id, CAPACITY - 1);
    await expectRefused(await registerRaw(w.id, { name: "Ada", email: email.toUpperCase() }), 409, w.id, CAPACITY);
  });

  it("R7: the same person can register for several workshops", async () => {
    const a = await createAcceptedWorkshop();
    const b = await createAcceptedWorkshop();
    const email = uniqueEmail("ada");
    await register(a.id, "Ada", email);
    const r = await register(b.id, "Ada", email.toUpperCase());
    expect(r.workshopId).toBe(b.id);
  });

  it("R8, D12, D13: name and email come back exactly as typed", async () => {
    const w = await createAcceptedWorkshop();
    const email = ` ${uniqueEmail("ADA").replace("example.com", "Example.COM")} `;
    const r = await register(w.id, "  Ada  Lovelace ", email);
    expect(r.email).toBe(email);
    expect(r.name).toBe("  Ada  Lovelace ");
    expect((await listRegistrations(w.id))[0]).toEqual(r);
  });

  it("R9: the list is in registration order and only this workshop's", async () => {
    const w = await createAcceptedWorkshop();
    const other = await createAcceptedWorkshop();
    const c = await register(w.id, "Charlie", uniqueEmail("c"));
    await register(other.id, "Other", uniqueEmail("o"));
    const a = await register(w.id, "Ada", uniqueEmail("a"));
    const b = await register(w.id, "Bob", uniqueEmail("b"));
    expect(await listRegistrations(w.id)).toEqual([c, a, b]);
  });

  it("D1: unknown talk is 404", async () => {
    expectStatus(await registerRaw(UNKNOWN_ID, { name: "Ada", email: uniqueEmail() }), 404);
    expectStatus(await get(`/workshops/${UNKNOWN_ID}/registrations`), 404);
  });

  it("D3: a non-integer id is 400", async () => {
    expectStatus(await registerRaw("abc", { name: "Ada", email: uniqueEmail() }), 400);
    expectStatus(await get("/workshops/abc/registrations"), 400);
  });

  it("D4: body missing, not JSON or not an object is 400", async () => {
    const w = await createAcceptedWorkshop();
    for (const body of [undefined, "", "not json", "[]", '"Ada"']) {
      await expectRefused(await registerRaw(w.id, body), 400, w.id, 0);
    }
  });

  it("D5, D6: name and email missing, null, blank or not a string are 400", async () => {
    const w = await createAcceptedWorkshop();
    const email = uniqueEmail();
    const bad: unknown[] = [
      { email },
      { name: null, email },
      { name: "", email },
      { name: "   ", email },
      { name: 42, email },
      { name: ["Ada"], email },
      { name: "Ada" },
      { name: "Ada", email: null },
      { name: "Ada", email: "" },
      { name: "Ada", email: "  " },
      { name: "Ada", email: 42 },
    ];
    for (const body of bad) {
      expectStatus(await registerRaw(w.id, body), 400);
    }
    expect(await listRegistrations(w.id)).toHaveLength(0);
  });

  it("D7: an email that isn't an email is 400", async () => {
    const w = await createAcceptedWorkshop();
    for (const email of ["ada", "ada@example", "ada.lovelace@example"]) {
      expectStatus(await registerRaw(w.id, { name: "Ada", email }), 400);
    }
    expect(await listRegistrations(w.id)).toHaveLength(0);
  });

  it("D14: unknown fields are ignored", async () => {
    const w = await createAcceptedWorkshop();
    const res = await registerRaw(w.id, { name: "Ada", email: uniqueEmail(), phone: "123" });
    expectStatus(res, 201);
    expect(Object.keys(res.json as object).sort()).toEqual(["email", "id", "name", "workshopId"]);
  });

  it("D17: the workshop's own speaker and co-speaker can register, and count towards the 20", async () => {
    const speaker = await createSpeaker();
    const co = await createSpeaker();
    const w = await createAcceptedTalk(speaker.id, "WORKSHOP");
    await addCoSpeaker(w.id, co.id);
    await register(w.id, speaker.name, speaker.email);
    await register(w.id, co.name, co.email);
    await fill(w.id, CAPACITY - 2);
    await expectRefused(await registerRaw(w.id, { name: "Late", email: uniqueEmail() }), 409, w.id, CAPACITY);
  });

  it("D18: 404 beats 400 beats 409", async () => {
    const speaker = await createSpeaker();
    const presentation = await createAcceptedTalk(speaker.id, "PRESENTATION");
    const submitted = await createTalk({ speakerId: speaker.id, format: "WORKSHOP" });
    expectStatus(await registerRaw(UNKNOWN_ID, "not json"), 404);
    expectStatus(await registerRaw(presentation.id, "not json"), 404);
    expectStatus(await registerRaw(presentation.id, {}), 404);
    await expectRefused(await registerRaw(submitted.id, { name: "Ada" }), 400, submitted.id, 0);
  });

  it("D19: there is no cancelling (405)", async () => {
    const w = await createAcceptedWorkshop();
    await register(w.id, "Ada", uniqueEmail());
    expectStatus(await del(`/workshops/${w.id}/registrations`), 405);
    expect(await listRegistrations(w.id)).toHaveLength(1);
  });
});
