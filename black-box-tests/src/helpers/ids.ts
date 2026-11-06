import { randomUUID } from "node:crypto";

export function uniqueId(prefix = "id"): string {
  return `${prefix}-${randomUUID()}`;
}

export function uniqueEmail(local = "speaker"): string {
  return `${local}-${randomUUID()}@example.com`;
}

export function uniqueName(prefix = "Speaker"): string {
  return `${prefix} ${randomUUID().slice(0, 8)}`;
}

export function uniqueReviewerId(): number {
  // Positive whole number; keep in a large range to avoid collisions across runs.
  return Math.floor(1_000_000_000 + Math.random() * 1_000_000_000);
}
