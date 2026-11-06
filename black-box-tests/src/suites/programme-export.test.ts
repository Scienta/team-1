// Programme export: specs/programme-export/spec.md. Test names carry the rule (R) or decision (D) they prove.
import { beforeAll, beforeEach, describe, expect, it } from "vitest";
import { expectStatus } from "../helpers/expect.js";
import {
  addCoSpeaker,
  clearWholeProgramme,
  createAcceptedTalk,
  createSpeaker,
  getProgrammeCsv,
  getProgrammeCsvRaw,
  getProgrammeExport,
  putProgramme,
  removeCoSpeaker,
  updateSpeaker,
  withdrawTalk,
} from "../helpers/factories.js";
import { request, waitForHealth } from "../helpers/http.js";
import { uniqueEmail } from "../helpers/ids.js";
import type { ProgrammeRow, Room, Slot, TalkFormat } from "../helpers/types.js";
import { ROOMS, SLOTS, SLOT_TIMES } from "../helpers/types.js";

const HEADER = "slot,start,end,room,talkId,title,format,speakers\n";

/** An accepted talk by a fresh speaker with this name, placed in the cell. */
async function placed(
  room: Room,
  slot: Slot,
  speakerName: string,
  title: string,
  format: TalkFormat = "PRESENTATION",
) {
  const speaker = await createSpeaker({ name: speakerName });
  const talk = await createAcceptedTalk(speaker.id, { title, format });
  await putProgramme(room, slot, talk.id);
  return { speaker, talk };
}

function csvLine(r: Omit<ProgrammeRow, "speakers"> & { speakers: string }): string {
  return `${r.slot},${r.start},${r.end},${r.room},${r.talkId},${r.title},${r.format},${r.speakers}\n`;
}

describe("programme-export", () => {
  beforeAll(async () => {
    await waitForHealth();
  });

  // The venue is fixed and shared; every test here starts from an empty programme.
  beforeEach(async () => {
    await clearWholeProgramme();
  });

  it("R11: empty programme is [] and the CSV is just the header", async () => {
    expect(await getProgrammeExport()).toEqual([]);
    expect(await getProgrammeCsv()).toBe(HEADER);
  });

  it("R1-R4, R8, R9, D18, D19: one row per talk, slot then room order, same in JSON and CSV", async () => {
    const s5r1 = await placed("R1", "S5", "Speaker A", "Talk S5 R1");
    const s1r3 = await placed("R3", "S1", "Speaker B", "Talk S1 R3");
    const s4r2 = await placed("R2", "S4", "Speaker D", "Workshop S4 R2", "WORKSHOP");
    const s1r1 = await placed("R1", "S1", "Speaker C", "Talk S1 R1");
    const s2r2 = await placed("R2", "S2", "Speaker E", "Talk S2 R2");

    const expected: ProgrammeRow[] = [
      ["S1", "R1", s1r1, "Talk S1 R1", "PRESENTATION", "Speaker C"],
      ["S1", "R3", s1r3, "Talk S1 R3", "PRESENTATION", "Speaker B"],
      ["S2", "R2", s2r2, "Talk S2 R2", "PRESENTATION", "Speaker E"],
      ["S4", "R2", s4r2, "Workshop S4 R2", "WORKSHOP", "Speaker D"],
      ["S5", "R1", s5r1, "Talk S5 R1", "PRESENTATION", "Speaker A"],
    ].map(([slot, room, p, title, format, name]) => ({
      slot: slot as Slot,
      start: SLOT_TIMES[slot as Slot].start,
      end: SLOT_TIMES[slot as Slot].end,
      room: room as Room,
      talkId: (p as { talk: { id: number } }).talk.id,
      title: title as string,
      format: format as TalkFormat,
      speakers: [name as string],
    }));

    const rows = await getProgrammeExport();
    expect(rows).toEqual(expected);
    for (const row of rows) {
      expect(Object.keys(row)).toEqual(["slot", "start", "end", "room", "talkId", "title", "format", "speakers"]);
      expect(Number.isInteger(row.talkId)).toBe(true);
    }

    const csv = await getProgrammeCsv();
    expect(csv).toBe(HEADER + expected.map((r) => csvLine({ ...r, speakers: r.speakers.join("; ") })).join(""));
    expect(csv).not.toContain("\r");
    expect(csv.endsWith("\n\n")).toBe(false);
  });

  it("R2: a full venue gives 15 rows and 16 CSV lines", async () => {
    for (const slot of SLOTS) {
      for (const room of ROOMS) {
        await placed(room, slot, `Full ${room}${slot}`, `Full ${room}${slot}`);
      }
    }
    const rows = await getProgrammeExport();
    expect(rows.map((r) => `${r.slot}${r.room}`)).toEqual(SLOTS.flatMap((s) => ROOMS.map((r) => `${s}${r}`)));
    expect((await getProgrammeCsv()).split("\n")).toHaveLength(17); // 16 lines + "" after the last \n
  });

  it("R6, A4: main speaker first, then co-speakers in add order; CSV joins with '; '", async () => {
    const { talk } = await placed("R1", "S2", "Grace Hopper", "Compilers");
    const ada = await createSpeaker({ name: "Ada Lovelace" });
    const linus = await createSpeaker({ name: "Linus" });
    await addCoSpeaker(talk.id, ada.id);
    await addCoSpeaker(talk.id, linus.id);
    // removed and re-added goes to the end
    await removeCoSpeaker(talk.id, ada.id);
    await addCoSpeaker(talk.id, ada.id);

    const [row] = await getProgrammeExport();
    expect(row.speakers).toEqual(["Grace Hopper", "Linus", "Ada Lovelace"]);
    expect(await getProgrammeCsv()).toBe(
      `${HEADER}S2,10:15,11:15,R1,${talk.id},Compilers,PRESENTATION,Grace Hopper; Linus; Ada Lovelace\n`,
    );
  });

  it("R5, D3, D4: speakers are the current ones (renamed, added, removed after scheduling)", async () => {
    const { speaker, talk } = await placed("R3", "S3", "Old Name", "Live data");
    const first = await createSpeaker({ name: "First Co" });
    await addCoSpeaker(talk.id, first.id);
    await updateSpeaker(speaker.id, { name: "New Name", email: uniqueEmail("renamed") });
    const second = await createSpeaker({ name: "Second Co" });
    await addCoSpeaker(talk.id, second.id);
    await removeCoSpeaker(talk.id, first.id);

    expect((await getProgrammeExport())[0].speakers).toEqual(["New Name", "Second Co"]);
    expect(await getProgrammeCsv()).toMatch(/,Live data,PRESENTATION,New Name; Second Co\n$/);
  });

  it("R10: comma and quotes are quoted with doubled quotes (the request's example)", async () => {
    const { talk } = await placed("R1", "S4", "Grace Hopper", 'Kotlin, "really" fast', "WORKSHOP");
    await addCoSpeaker(talk.id, (await createSpeaker({ name: "Ada Lovelace" })).id);
    expect((await getProgrammeExport())[0].title).toBe('Kotlin, "really" fast'); // D5
    expect(await getProgrammeCsv()).toBe(
      `${HEADER}S4,13:30,15:30,R1,${talk.id},"Kotlin, ""really"" fast",WORKSHOP,Grace Hopper; Ada Lovelace\n`,
    );
  });

  it("R10: the joined speakers field is quoted when a name has a comma", async () => {
    const { talk } = await placed("R3", "S5", "Hopper, Grace", "Names");
    await addCoSpeaker(talk.id, (await createSpeaker({ name: "Ada" })).id);
    expect(await getProgrammeCsv()).toMatch(/,Names,PRESENTATION,"Hopper, Grace; Ada"\n$/);
  });

  it("R10, D9: line breaks inside a field are quoted and kept byte for byte", async () => {
    const lf = await placed("R1", "S1", "LF", "Line one\nLine two");
    const crlf = await placed("R2", "S1", "CRLF", "Win one\r\nWin two");
    expect(await getProgrammeCsv()).toBe(
      HEADER +
        `S1,09:00,10:00,R1,${lf.talk.id},"Line one\nLine two",PRESENTATION,LF\n` +
        `S1,09:00,10:00,R2,${crlf.talk.id},"Win one\r\nWin two",PRESENTATION,CRLF\n`,
    );
  });

  it("D5, D7, D8: ';' and surrounding spaces are written as they are, unquoted", async () => {
    const { talk } = await placed("R1", "S3", "  Spaced; Name ", "  padded title ");
    const [row] = await getProgrammeExport();
    expect(row.title).toBe("  padded title ");
    expect(row.speakers).toEqual(["  Spaced; Name "]);
    expect(await getProgrammeCsv()).toBe(`${HEADER}S3,11:30,12:30,R1,${talk.id},  padded title ,PRESENTATION,  Spaced; Name \n`);
  });

  it("R7: UTF-8 text survives in both views", async () => {
    await placed("R2", "S5", "Bjørn Ærø", "Smørbrød og kaffe ☕");
    expect((await getProgrammeExport())[0].title).toBe("Smørbrød og kaffe ☕");
    expect(await getProgrammeCsv()).toMatch(/,Smørbrød og kaffe ☕,PRESENTATION,Bjørn Ærø\n$/);
  });

  it("D2: a talk withdrawn after scheduling stays, with no status column", async () => {
    const { speaker, talk } = await placed("R2", "S2", "Withdrawer", "Gone soon");
    await withdrawTalk(talk.id, speaker.id);
    const rows = await getProgrammeExport();
    expect(rows).toHaveLength(1);
    expect(rows[0]).not.toHaveProperty("status");
    expect(await getProgrammeCsv()).toBe(`${HEADER}S2,10:15,11:15,R2,${talk.id},Gone soon,PRESENTATION,Withdrawer\n`);
  });

  it("D6: two speakers with the same name are both listed", async () => {
    const { talk } = await placed("R1", "S5", "Kim", "Twins");
    await addCoSpeaker(talk.id, (await createSpeaker({ name: "Kim" })).id);
    expect((await getProgrammeExport())[0].speakers).toEqual(["Kim", "Kim"]);
  });

  it("R7, D10, D11: CSV is text/csv; charset=UTF-8 whatever the Accept header, never 406", async () => {
    await placed("R1", "S1", "Acc", "Accept");
    const expected = await getProgrammeCsv();
    for (const accept of [undefined, "application/json", "text/html", "*/*", "text/csv", "application/xml"]) {
      const res = expectStatus(await getProgrammeCsvRaw(accept ? { Accept: accept } : undefined), 200);
      const type = (res.headers.get("content-type") ?? "").toLowerCase();
      expect(type.startsWith("text/csv"), `${accept} gave ${type}`).toBe(true);
      expect(type.replace(/ /g, "")).toContain("charset=utf-8");
      expect(res.text).toBe(expected);
    }
  });

  it("D12: /programme is JSON whatever the Accept header, never 406", async () => {
    await placed("R1", "S1", "Js", "Json");
    for (const accept of ["text/csv", "text/html", "*/*", "application/xml"]) {
      const res = await request<ProgrammeRow[]>("/programme", { headers: { Accept: accept } });
      expect(res.status, accept).toBe(200);
      expect(res.headers.get("content-type") ?? "").toMatch(/^application\/json/);
      expect(res.json).toHaveLength(1);
    }
  });

  it("D1: export paths don't shadow /programme/{room}/{slot}", async () => {
    const { talk } = await placed("R1", "S1", "Cell", "Cell talk");
    const cell = await request<{ talkId: number }>("/programme/R1/S1");
    expect(cell.status).toBe(200);
    expect(cell.json.talkId).toBe(talk.id);
    expect((await request("/programme/R2/S1")).status).toBe(404);
  });

  it("D14: query parameters are ignored", async () => {
    await placed("R1", "S1", "Qp", "Query");
    expect((await getProgrammeCsvRaw(undefined, "/programme.csv?room=R2&format=json")).text).toBe(
      await getProgrammeCsv(),
    );
    expect((await request("/programme?slot=S5")).json).toEqual(await getProgrammeExport());
  });

  it("D15: /programme/ is the same as /programme", async () => {
    await placed("R1", "S1", "Ts", "Slash");
    const res = await request<ProgrammeRow[]>("/programme/");
    expect(res.status).toBe(200);
    expect(res.json).toEqual(await getProgrammeExport());
  });

  it("D16: POST/PUT/DELETE on the export paths are 405", async () => {
    for (const method of ["POST", "PUT", "DELETE"]) {
      for (const path of ["/programme", "/programme.csv"]) {
        expect((await request(path, { method, body: {} })).status, `${method} ${path}`).toBe(405);
      }
    }
  });

  it("D17: HEAD is 200 with no body", async () => {
    const csv = await request("/programme.csv", { method: "HEAD" });
    expect(csv.status).toBe(200);
    expect(csv.headers.get("content-type") ?? "").toMatch(/^text\/csv/);
    expect(csv.text).toBe("");
    expect((await request("/programme", { method: "HEAD" })).status).toBe(200);
  });

  it("Concurrency: exports during parallel scheduling edits are always 200 and well formed", async () => {
    const talks: number[] = [];
    for (let i = 0; i < 6; i++) {
      talks.push((await createAcceptedTalk((await createSpeaker({ name: `Par ${i}` })).id, { title: `Par ${i}` })).id);
    }
    const edits: Promise<{ status: number }>[] = [];
    const exports: Promise<{ status: number; text: string; csv: boolean }>[] = [];
    for (let round = 0; round < 4; round++) {
      talks.forEach((id, i) => {
        const path = `/programme/${ROOMS[i % 3]}/${i < 3 ? "S1" : "S2"}`;
        edits.push(request(path, round % 2 === 0 ? { method: "PUT", body: { talkId: id } } : { method: "DELETE" }));
      });
      for (let i = 0; i < 3; i++) {
        exports.push(request("/programme").then((r) => ({ ...r, csv: false })));
        exports.push(request("/programme.csv").then((r) => ({ ...r, csv: true })));
      }
    }
    for (const e of await Promise.all(edits)) expect([200, 404, 409]).toContain(e.status);
    for (const r of await Promise.all(exports)) {
      expect(r.status, r.text).toBe(200);
      if (r.csv) {
        expect(r.text.startsWith(HEADER)).toBe(true);
        expect(r.text.endsWith("\n")).toBe(true);
      } else {
        expect(Array.isArray(JSON.parse(r.text))).toBe(true);
      }
    }
    const rows = await getProgrammeExport();
    expect((await getProgrammeCsv()).split("\n")).toHaveLength(rows.length + 2);
  });
});
