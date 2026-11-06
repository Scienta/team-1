import { baseUrl } from "./env.js";

export type HttpResponse<T = unknown> = {
  status: number;
  ok: boolean;
  headers: Headers;
  text: string;
  json: T;
};

export type RequestOptions = {
  method?: string;
  body?: unknown;
  headers?: Record<string, string>;
  /** Override default connect retries (default 8). */
  retries?: number;
};

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

function joinUrl(root: string, path: string): string {
  if (path.startsWith("http://") || path.startsWith("https://")) return path;
  const p = path.startsWith("/") ? path : `/${path}`;
  return `${root}${p}`;
}

function isConnectError(err: unknown): boolean {
  if (!(err instanceof Error)) return false;
  const cause = (err as Error & { cause?: { code?: string } }).cause;
  const code = cause?.code ?? (err as Error & { code?: string }).code;
  return (
    code === "ECONNREFUSED" ||
    code === "ECONNRESET" ||
    code === "UND_ERR_CONNECT_TIMEOUT" ||
    /fetch failed/i.test(err.message)
  );
}

export async function request<T = unknown>(
  path: string,
  options: RequestOptions = {},
): Promise<HttpResponse<T>> {
  const url = joinUrl(baseUrl(), path);
  const method = (options.method ?? "GET").toUpperCase();
  const headers: Record<string, string> = { ...(options.headers ?? {}) };
  let body: string | undefined;
  if (options.body !== undefined) {
    headers["Content-Type"] = headers["Content-Type"] ?? "application/json";
    body = typeof options.body === "string" ? options.body : JSON.stringify(options.body);
  }

  const retries = options.retries ?? 8;
  let lastErr: unknown;
  for (let attempt = 0; attempt <= retries; attempt++) {
    try {
      const res = await fetch(url, { method, headers, body });
      const text = await res.text();
      let json: T = undefined as T;
      if (text.length > 0) {
        try {
          json = JSON.parse(text) as T;
        } catch {
          json = text as T;
        }
      }
      return {
        status: res.status,
        ok: res.ok,
        headers: res.headers,
        text,
        json,
      };
    } catch (err) {
      lastErr = err;
      if (!isConnectError(err) || attempt === retries) throw err;
      await sleep(250 * (attempt + 1));
    }
  }
  throw lastErr;
}

export const get = <T = unknown>(path: string, opts?: Omit<RequestOptions, "method" | "body">) =>
  request<T>(path, { ...opts, method: "GET" });

export const post = <T = unknown>(path: string, body?: unknown, opts?: Omit<RequestOptions, "method" | "body">) =>
  request<T>(path, { ...opts, method: "POST", body });

export const put = <T = unknown>(path: string, body?: unknown, opts?: Omit<RequestOptions, "method" | "body">) =>
  request<T>(path, { ...opts, method: "PUT", body });

export const del = <T = unknown>(path: string, opts?: Omit<RequestOptions, "method" | "body">) =>
  request<T>(path, { ...opts, method: "DELETE" });

export async function waitForHealth(timeoutMs = 60_000): Promise<void> {
  const start = Date.now();
  let last = "";
  while (Date.now() - start < timeoutMs) {
    try {
      const res = await get<{ status?: string }>("/health", { retries: 0 });
      if (res.status === 200 && res.json?.status === "ok") return;
      last = `${res.status} ${res.text}`;
    } catch (err) {
      last = err instanceof Error ? err.message : String(err);
    }
    await sleep(500);
  }
  throw new Error(`Timed out waiting for /health ok (${timeoutMs}ms). Last: ${last}`);
}
