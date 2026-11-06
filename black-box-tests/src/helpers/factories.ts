import { expectJson, expectStatus } from "./expect.js";
import { del, get, post, put } from "./http.js";
import { uniqueEmail, uniqueName, uniqueReviewerId } from "./ids.js";
import type { Decision, Rating, Registration, Speaker, Talk, TalkFormat, TalkLevel } from "./types.js";

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

/** PUT /speakers/{id} (the admin UI's typo fix): name, email and bio. */
export async function putSpeakerRaw(id: number, body: unknown) {
  return put(`/speakers/${id}`, body);
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
export async function createAcceptableTalk(speakerId: number): Promise<Talk> {
  const talk = await createTalk({ speakerId });
  await rateTalk(talk.id, 4);
  await rateTalk(talk.id, 5);
  return getTalk(talk.id);
}

/** A talk of the given format, rated by two reviewers and accepted. */
export async function createAcceptedTalk(speakerId: number, format: TalkFormat): Promise<Talk> {
  const talk = await createTalk({ speakerId, format });
  await rateTalk(talk.id, 4);
  await rateTalk(talk.id, 5);
  return decideTalk(talk.id, "ACCEPTED");
}

/** An accepted workshop with a speaker of its own (workshop-registration). */
export async function createAcceptedWorkshop(): Promise<Talk> {
  const speaker = await createSpeaker();
  return createAcceptedTalk(speaker.id, "WORKSHOP");
}

/** POST /workshops/{id}/registrations with any body (a string is sent verbatim; undefined sends no body). */
export async function registerRaw(workshopId: number | string, body?: unknown) {
  return post<Registration>(`/workshops/${workshopId}/registrations`, body);
}

/** Registers for a workshop. Expects 201 and returns the registration. */
export async function register(workshopId: number, name: string, email: string): Promise<Registration> {
  return expectJson(await registerRaw(workshopId, { name, email }), 201);
}

/** A workshop's registrations. Expects 200. */
export async function listRegistrations(workshopId: number): Promise<Registration[]> {
  return expectJson(await get<Registration[]>(`/workshops/${workshopId}/registrations`), 200);
}

export { expectStatus };
