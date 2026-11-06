import { expect } from "vitest";
import type { HttpResponse } from "./http.js";

export function expectStatus<T>(res: HttpResponse<T>, status: number): HttpResponse<T> {
  expect(res.status, res.text).toBe(status);
  return res;
}

export function expectJson<T>(res: HttpResponse<T>, status: number): T {
  expectStatus(res, status);
  expect(res.json, "expected JSON body").toBeTypeOf("object");
  return res.json;
}

export function expectMatch(value: string, re: RegExp, label = "value"): void {
  expect(value, `${label}: ${value}`).toMatch(re);
}
