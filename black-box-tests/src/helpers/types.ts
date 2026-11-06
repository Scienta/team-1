export type Speaker = {
  id: number;
  name: string;
  email: string;
  bio: string | null;
};

export type TalkFormat = "LIGHTNING_10" | "LIGHTNING_20" | "PRESENTATION" | "WORKSHOP";
export type TalkLevel = "BEGINNER" | "INTERMEDIATE" | "ADVANCED";

export type Talk = {
  id: number;
  title: string;
  format: TalkFormat;
  level: TalkLevel;
  speakerId: number;
  durationMinutes: number;
  status: string;
  submittedAt: string;
  submittedOn: string;
  averageRating: number | null;
  ratingCount: number;
  coSpeakerIds?: number[];
};

export type Rating = {
  talkId: number;
  reviewerId: number;
  score: number;
  ratedAtDisplay: string;
};

export type Health = {
  status: string;
  version: string;
};
