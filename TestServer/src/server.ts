import { randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";
import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import { inspectRequest, type Headers, type InspectedRequest, type Spec } from "./spec.ts";

/** The server's own page and API live under this prefix; every other path is treated as an endpoint. */
export const API_PREFIX = "/_/";
const MAX_BODY_BYTES = 5 * 1024 * 1024;
const INDEX_PATH = new URL("../index.html", import.meta.url);

export interface ResponseConfiguration {
  status: number;
  retryAfter: string | null;
}

export interface RecordedRequest extends InspectedRequest {
  seq: number;
  receivedAt: string;
  method: string;
  target: string;
  headers: Headers;
  body: string;
  response: ResponseConfiguration;
}

/** Every request received, and how to respond to the next one. */
export class Recorder {
  response: ResponseConfiguration = { status: 204, retryAfter: null };
  generation = randomUUID();
  readonly #maxRequests: number;
  #requests: RecordedRequest[] = [];
  #nextSeq = 1;
  #storedIds = new Set<string>();

  constructor(maxRequests = 10_000) {
    this.#maxRequests = maxRequests;
  }

  clear(): void {
    this.generation = randomUUID();
    this.#requests = [];
    this.#nextSeq = 1;
    this.#storedIds.clear();
  }

  /** Records `request` and returns the response to send: the configured one, unless `status` is given. */
  record(request: Omit<RecordedRequest, "seq" | "response">, status?: number): ResponseConfiguration {
    const response = status === undefined ? { ...this.response } : { status, retryAfter: null };
    const seen = new Set<string>();
    for (const event of request.events) {
      if (typeof event.id !== "string") continue;
      // A duplicate is an event a 2xx response has already told a client was stored.
      event.duplicate = this.#storedIds.has(event.id) || seen.has(event.id);
      seen.add(event.id);
    }
    if (response.status >= 200 && response.status < 300) {
      for (const id of seen) this.#storedIds.add(id);
    }
    this.#requests.push({ ...request, seq: this.#nextSeq++, response });
    this.#requests.splice(0, Math.max(0, this.#requests.length - this.#maxRequests));
    return response;
  }

  /** Requests after `after`, or all of them if the requests were cleared since `generation`. */
  snapshot(after: number, generation: string | null) {
    const reset = generation !== this.generation;
    const requests = reset ? this.#requests : this.#requests.filter((request) => request.seq > after);
    return { generation: this.generation, reset, requests, response: this.response };
  }

  setResponse(status: unknown, retryAfter: unknown): ResponseConfiguration {
    if (!Number.isInteger(status) || (status as number) < 200 || (status as number) > 599) {
      throw new Error("status must be an integer from 200 to 599");
    }
    if (retryAfter !== null && retryAfter !== undefined && typeof retryAfter !== "string") {
      throw new Error("retryAfter must be a string or null");
    }
    this.response = { status: status as number, retryAfter: retryAfter || null };
    return this.response;
  }
}

export interface ServerOptions {
  log?: (line: string) => void;
}

export function createTestServer(spec: Spec, recorder: Recorder, { log = () => {} }: ServerOptions = {}): Server {
  return createServer((request, response) => {
    handle(spec, recorder, request, response, log).catch((error: unknown) => {
      log(`Couldn't handle ${request.method} ${request.url}: ${error}`);
      if (!response.headersSent) send(response, 500, "Internal server error\n", "text/plain; charset=utf-8");
    });
  });
}

async function handle(
  spec: Spec,
  recorder: Recorder,
  request: IncomingMessage,
  response: ServerResponse,
  log: (line: string) => void,
): Promise<void> {
  const method = request.method ?? "GET";
  const target = request.url ?? "/";
  if (target.startsWith(API_PREFIX)) return handleApi(recorder, method, target, request, response);
  if (method === "GET" && target === "/") {
    return send(response, 200, readFileSync(INDEX_PATH), "text/html; charset=utf-8");
  }

  const { body, tooLarge } = await readBody(request);
  const headers: Headers = [];
  for (let i = 0; i < request.rawHeaders.length; i += 2) headers.push([request.rawHeaders[i], request.rawHeaders[i + 1]]);
  const inspected = inspectRequest(spec, method, target, headers, body);
  if (!inspected) return send(response, 404, "Not a Davy request\n", "text/plain; charset=utf-8");
  if (tooLarge) inspected.problems.push(`The body is over ${MAX_BODY_BYTES} bytes, so the server didn't keep it`);

  const receivedAt = new Date().toISOString();
  const configured = recorder.record(
    { ...inspected, receivedAt, method, target, headers, body: body.toString("utf8") },
    tooLarge ? 413 : undefined,
  );
  const responseHeaders: Record<string, string> = { "Cache-Control": "no-store" };
  if (configured.retryAfter) responseHeaders["Retry-After"] = configured.retryAfter;
  if (configured.status !== 204) responseHeaders["Content-Length"] = "0";
  response.writeHead(configured.status, responseHeaders).end();
  log(`${receivedAt} ${method} ${target} ${configured.status}`);
}

async function handleApi(
  recorder: Recorder,
  method: string,
  target: string,
  request: IncomingMessage,
  response: ServerResponse,
): Promise<void> {
  const url = new URL(target, "http://localhost");
  switch (`${method} ${url.pathname.slice(API_PREFIX.length).replace(/\/$/, "")}`) {
    case "GET requests": {
      const after = Number(url.searchParams.get("after")) || 0;
      return sendJson(response, 200, recorder.snapshot(after, url.searchParams.get("generation")));
    }
    case "DELETE requests":
      recorder.clear();
      return send(response, 204);
    case "GET response":
      return sendJson(response, 200, recorder.response);
    case "PUT response": {
      const { body } = await readBody(request);
      try {
        const payload = JSON.parse(body.toString("utf8") || "{}");
        return sendJson(response, 200, recorder.setResponse(payload?.status, payload?.retryAfter ?? null));
      } catch (error) {
        return send(response, 400, `${(error as Error).message}\n`, "text/plain; charset=utf-8");
      }
    }
    default:
      return send(response, 404, "Not found\n", "text/plain; charset=utf-8");
  }
}

/** The request's body, or nothing if it's too large to keep. */
async function readBody(request: IncomingMessage): Promise<{ body: Buffer; tooLarge: boolean }> {
  const chunks: Buffer[] = [];
  let size = 0;
  for await (const chunk of request as AsyncIterable<Buffer>) {
    size += chunk.length;
    if (size <= MAX_BODY_BYTES) chunks.push(chunk);
  }
  const tooLarge = size > MAX_BODY_BYTES;
  return { body: tooLarge ? Buffer.alloc(0) : Buffer.concat(chunks), tooLarge };
}

function sendJson(response: ServerResponse, status: number, value: unknown): void {
  send(response, status, JSON.stringify(value), "application/json");
}

function send(response: ServerResponse, status: number, body: string | Buffer = "", contentType?: string): void {
  const headers: Record<string, string> = { "Cache-Control": "no-store" };
  if (status !== 204) {
    if (contentType) headers["Content-Type"] = contentType;
    headers["Content-Length"] = String(Buffer.byteLength(body));
  }
  response.writeHead(status, headers).end(status === 204 ? undefined : body);
}
