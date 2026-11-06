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

export type Room = "R1" | "R2" | "R3";
export type Slot = "S1" | "S2" | "S3" | "S4" | "S5";

export type ProgrammeEntry = {
  room: Room;
  slot: Slot;
  start: string;
  end: string;
  talkId: number;
};

/** Fixed venue times from specs/scheduling (R1). */
export const SLOT_TIMES: Record<Slot, { start: string; end: string }> = {
  S1: { start: "09:00", end: "10:00" },
  S2: { start: "10:15", end: "11:15" },
  S3: { start: "11:30", end: "12:30" },
  S4: { start: "13:30", end: "15:30" },
  S5: { start: "15:45", end: "16:45" },
};
