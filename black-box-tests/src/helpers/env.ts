const DEFAULT_BASE_URL = "http://localhost:8080";

/**
 * App under test. Prefer {@code CFP_BASE_URL}: Cursor/other tools often set {@code BASE_URL}
 * to a relative path like {@code "/"}, which is not a usable API origin.
 */
export function baseUrl(): string {
  const raw = (process.env.CFP_BASE_URL ?? process.env.BASE_URL)?.trim() ?? "";
  if (!/^https?:\/\//i.test(raw)) {
    return DEFAULT_BASE_URL;
  }
  return raw.replace(/\/$/, "");
}
