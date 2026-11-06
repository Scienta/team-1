export type Speaker = {
  id: number;
  name: string;
  email: string;
  bio: string | null;
};

export type TalkFormat = "LIGHTNING_10" | "LIGHTNING_20" | "PRESENTATION" | "WORKSHOP";
export type TalkLevel = "BEGINNER" | "INTERMEDIATE" | "ADVANCED";

export type TalkStatus = "SUBMITTED" | "ACCEPTED" | "REJECTED" | "WAITLISTED" | "WITHDRAWN";
export type Decision = "ACCEPTED" | "REJECTED" | "WAITLISTED";

export type Talk = {
  id: number;
  title: string;
  format: TalkFormat;
  level: TalkLevel;
  speakerId: number;
  durationMinutes: number;
  status: TalkStatus;
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

export type Registration = {
  id: number;
  workshopId: number;
  name: string;
  email: string;
};
