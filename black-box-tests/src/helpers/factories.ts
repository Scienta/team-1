import { expectJson, expectStatus } from "./expect.js";
import { del, get, post, put } from "./http.js";
import { uniqueEmail, uniqueName, uniqueReviewerId } from "./ids.js";
import type {
  Decision,
  ProgrammeEntry,
  Rating,
  Room,
  Slot,
  Speaker,
  Talk,
  TalkFormat,
  TalkLevel,
} from "./types.js";

export type NewSpeakerInput = {
  name?: string;
  email?: string;
  bio?: string | null;
};

export type NewTalkInput = {
  title?: string;
  format?: TalkFormat;
  level?: TalkLevel;
  speakerId: number;
  /** Ignored by API; useful to assert duration is derived. */
  durationMinutes?: number;
};

export async function createSpeaker(input: NewSpeakerInput = {}): Promise<Speaker> {
  const body = {
    name: input.name ?? uniqueName(),
    email: input.email ?? uniqueEmail(),
    ...(input.bio !== undefined ? { bio: input.bio } : {}),
  };
  return expectJson(await post<Speaker>("/speakers", body), 201);
}

export async function getSpeaker(id: number, pathSuffix = ""): Promise<Speaker> {
  return expectJson(await get<Speaker>(`/speakers/${id}${pathSuffix}`), 200);
}

export async function createTalk(input: NewTalkInput): Promise<Talk> {
  const body: Record<string, unknown> = {
    title: input.title ?? `Talk ${uniqueName("t")}`,
    format: input.format ?? "PRESENTATION",
    level: input.level ?? "BEGINNER",
    speakerId: input.speakerId,
  };
  if (input.durationMinutes !== undefined) body.durationMinutes = input.durationMinutes;
  return expectJson(await post<Talk>("/talks", body), 201);
}

export async function getTalk(id: number): Promise<Talk> {
  return expectJson(await get<Talk>(`/talks/${id}`), 200);
}

export async function listTalks(query: { speakerId?: number; sort?: string } = {}): Promise<Talk[]> {
  const params = new URLSearchParams();
  if (query.speakerId !== undefined) params.set("speakerId", String(query.speakerId));
  if (query.sort !== undefined) params.set("sort", query.sort);
  const q = params.toString();
  return expectJson(await get<Talk[]>(`/talks${q ? `?${q}` : ""}`), 200);
}

export async function listSpeakerTalks(speakerId: number): Promise<Talk[]> {
  return expectJson(await get<Talk[]>(`/speakers/${speakerId}/talks`), 200);
}

export async function rateTalk(
  talkId: number,
  score: number,
  reviewerId = uniqueReviewerId(),
): Promise<Rating> {
  return expectJson(
    await put<Rating>(`/talks/${talkId}/ratings/${reviewerId}`, { score }),
    200,
  );
}

export async function postSpeakerRaw(body: unknown, path = "/speakers") {
  return post(path, body);
}

export async function postTalkRaw(body: unknown) {
  return post("/talks", body);
}

export async function putRatingRaw(talkId: number, reviewerId: number, body: unknown) {
  return put(`/talks/${talkId}/ratings/${reviewerId}`, body);
}

export async function addCoSpeaker(talkId: number, speakerId: number): Promise<Talk> {
  return expectJson(await put<Talk>(`/talks/${talkId}/co-speakers/${speakerId}`), 200);
}

export async function removeCoSpeaker(talkId: number, speakerId: number): Promise<Talk> {
  return expectJson(await del<Talk>(`/talks/${talkId}/co-speakers/${speakerId}`), 200);
}

export async function putCoSpeakerRaw(talkId: number, speakerId: number) {
  return put(`/talks/${talkId}/co-speakers/${speakerId}`);
}

export async function deleteCoSpeakerRaw(talkId: number, speakerId: number) {
  return del(`/talks/${talkId}/co-speakers/${speakerId}`);
}

/** Committee decision (acceptance). Expects 200 and returns the talk. */
export async function decideTalk(talkId: number, status: Decision): Promise<Talk> {
  return expectJson(await put<Talk>(`/talks/${talkId}/status`, { status }), 200);
}

/** PUT /talks/{id}/status with any body (a string is sent verbatim; undefined sends no body). */
export async function decideTalkRaw(talkId: number, body?: unknown) {
  return put<Talk>(`/talks/${talkId}/status`, body);
}

/** Withdrawal by a speaker (acceptance). Expects 200 and returns the talk. */
export async function withdrawTalk(talkId: number, speakerId: number): Promise<Talk> {
  return expectJson(await post<Talk>(`/talks/${talkId}/withdraw`, { speakerId }), 200);
}

/** POST /talks/{id}/withdraw with any body (a string is sent verbatim; undefined sends no body). */
export async function withdrawTalkRaw(talkId: number, body?: unknown) {
  return post<Talk>(`/talks/${talkId}/withdraw`, body);
}

/** A talk rated by two different reviewers, so it can be accepted (acceptance R3). */
export async function createAcceptableTalk(
  speakerId: number,
  input: Omit<NewTalkInput, "speakerId"> = {},
): Promise<Talk> {
  const talk = await createTalk({ ...input, speakerId });
  await rateTalk(talk.id, 4);
  await rateTalk(talk.id, 5);
  return getTalk(talk.id);
}

/** Acceptable talk already decided ACCEPTED (scheduling prerequisite). */
export async function createAcceptedTalk(
  speakerId: number,
  input: Omit<NewTalkInput, "speakerId"> = {},
): Promise<Talk> {
  const talk = await createAcceptableTalk(speakerId, input);
  return decideTalk(talk.id, "ACCEPTED");
}

function programmePath(room: string, slot: string, trailingSlash = false): string {
  return `/programme/${room}/${slot}${trailingSlash ? "/" : ""}`;
}

/** Best-effort clear so black-box tests can reuse fixed venue cells. */
export async function clearProgrammeCell(room: Room | string, slot: Slot | string): Promise<void> {
  await del(programmePath(room, slot));
}

export async function putProgramme(
  room: Room | string,
  slot: Slot | string,
  talkId: number,
): Promise<ProgrammeEntry> {
  return expectJson(
    await put<ProgrammeEntry>(programmePath(room, slot), { talkId }),
    200,
  );
}

export async function getProgramme(room: Room | string, slot: Slot | string): Promise<ProgrammeEntry> {
  return expectJson(await get<ProgrammeEntry>(programmePath(room, slot)), 200);
}

export async function deleteProgramme(
  room: Room | string,
  slot: Slot | string,
): Promise<ProgrammeEntry> {
  return expectJson(await del<ProgrammeEntry>(programmePath(room, slot)), 200);
}

export async function putProgrammeRaw(room: string, slot: string, body?: unknown, trailingSlash = false) {
  return put<ProgrammeEntry>(programmePath(room, slot, trailingSlash), body);
}

export async function getProgrammeRaw(room: string, slot: string, trailingSlash = false) {
  return get<ProgrammeEntry>(programmePath(room, slot, trailingSlash));
}

export async function deleteProgrammeRaw(room: string, slot: string, trailingSlash = false) {
  return del<ProgrammeEntry>(programmePath(room, slot, trailingSlash));
}

export { expectStatus };
