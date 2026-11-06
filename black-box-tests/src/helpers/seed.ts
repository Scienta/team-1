import { createSpeaker, createTalk, getTalk, rateTalk } from "./factories.js";
import type { Rating, Speaker, Talk } from "./types.js";

export type BaselineSeed = {
  speaker: Speaker;
  talk: Talk;
  rating: Rating;
};

/** Shared read-only baseline for a suite file. Safe across re-runs (unique emails). */
export async function seedBaseline(): Promise<BaselineSeed> {
  const speaker = await createSpeaker({ bio: "Baseline seed speaker" });
  const created = await createTalk({
    speakerId: speaker.id,
    title: "Baseline seed talk",
    format: "PRESENTATION",
    level: "INTERMEDIATE",
  });
  const rating = await rateTalk(created.id, 4);
  const talk = await getTalk(created.id);
  return { speaker, talk, rating };
}
