import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import type { AddressInfo } from "node:net";
import { afterEach, beforeEach, describe, test } from "node:test";
import { fileURLToPath } from "node:url";
import { createTestServer, Recorder, type RecordedRequest } from "../src/server.ts";
import { inspectRequest, Spec, type Headers } from "../src/spec.ts";

interface Fixture {
  name: string;
  session: object;
  events: object[];
  sentAt: string;
  expected: { method: string; url: string; headers: Record<string, string>; body?: unknown };
}

const spec = new Spec(fileURLToPath(new URL("../../Spec/openapi.yaml", import.meta.url)));
const fixtures: Fixture[] = JSON.parse(readFileSync(new URL("../../Spec/fixtures.json", import.meta.url), "utf8")).cases;

function fixtureRequest({ expected }: Fixture): [method: string, target: string, headers: Headers, body: Buffer] {
  const target = expected.url.replace(/^https?:\/\/[^/]+/, "");
  const body = expected.body === undefined ? Buffer.alloc(0) : Buffer.from(JSON.stringify(expected.body));
  return [expected.method, target, Object.entries(expected.headers), body];
}

function assertProblem(problems: string[], expected: string): void {
  assert.ok(problems.some((problem) => problem.includes(expected)), `No problem includes "${expected}": ${JSON.stringify(problems)}`);
}

describe("inspectRequest", () => {
  for (const fixture of fixtures) {
    test(`reads the fixture "${fixture.name}" without problems`, () => {
      const request = inspectRequest(spec, ...fixtureRequest(fixture))!;
      assert.deepEqual(request.problems, []);
      assert.deepEqual(request.session, fixture.session);
      assert.deepEqual(request.events, fixture.events);
      assert.equal(request.sentAt, fixture.sentAt);
    });
  }

  test("reports JSON batches that break the spec", () => {
    const body = Buffer.from(JSON.stringify({
      session: { id: "not-a-uuid", os: "iOS", osVersion: "26.1.0" },
      events: [{ id: "1B4E28BA-2FA1-11D2-883F-0016D3CCA427", type: "warning", name: "a", timestamp: "2026-11-09T11:38:55.780Z", extra: "1" }],
    }));
    const { problems } = inspectRequest(spec, "POST", "/events", [], body)!;
    assertProblem(problems, 'The Content-Type is ""');
    assertProblem(problems, 'The User-Agent is ""');
    assertProblem(problems, 'The Accept-Language is ""');
    assertProblem(problems, "body.session.id doesn't match");
    assertProblem(problems, "body.events[0].type isn't one of");
    assertProblem(problems, "body.events[0] has a property that isn't in the spec: extra");
  });
});

describe("server", () => {
  let server: ReturnType<typeof createTestServer>;
  let base: string;

  beforeEach(async () => {
    server = createTestServer(spec, new Recorder());
    await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
    base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  });

  afterEach(async () => {
    server.closeAllConnections();
    await new Promise((resolve) => server.close(resolve));
  });

  const send = ([method, target, headers, body]: ReturnType<typeof fixtureRequest>) =>
    fetch(base + target, { method, headers, body: body.length > 0 ? body.toString("utf8") : undefined });

  const setResponse = async (status: number, retryAfter: string | null = null) => {
    const response = await fetch(`${base}/_/response`, { method: "PUT", body: JSON.stringify({ status, retryAfter }) });
    assert.equal(response.status, 200);
  };

  const requests = async (): Promise<RecordedRequest[]> => (await (await fetch(`${base}/_/requests`)).json()).requests;

  test("records every fixture and responds as configured", async () => {
    for (const fixture of fixtures) {
      const response = await send(fixtureRequest(fixture));
      assert.equal(response.status, 204, fixture.name);
      assert.equal(response.headers.get("Cache-Control"), "no-store");
    }
    await setResponse(429, "30");
    const throttled = await send(fixtureRequest(fixtures[0]));
    assert.equal(throttled.status, 429);
    assert.equal(throttled.headers.get("Retry-After"), "30");

    const recorded = await requests();
    assert.deepEqual(recorded.map((r) => r.sentAt), [...fixtures.map((f) => f.sentAt), fixtures[0].sentAt]);
    assert.ok(recorded.every((r) => r.problems.length === 0));
    // The first fixture was stored, so its events arriving again are duplicates.
    assert.deepEqual(recorded.at(-1)!.events.map((e) => e.duplicate), [true, true]);
  });

  test("marks events as duplicates only once they're stored", async () => {
    const batch = fixtureRequest(fixtures[0]);
    await setResponse(503, "5");
    await send(batch);
    await setResponse(204);
    await send(batch);
    await send(batch);
    const duplicates = (await requests()).map((r) => r.events.map((e) => e.duplicate));
    assert.deepEqual(duplicates, [[false, false], [false, false], [true, true]]);
  });

  test("clears the requests", async () => {
    await send(fixtureRequest(fixtures[0]));
    const { generation } = await (await fetch(`${base}/_/requests`)).json();
    assert.equal((await fetch(`${base}/_/requests`, { method: "DELETE" })).status, 204);
    const snapshot = await (await fetch(`${base}/_/requests?after=1&generation=${generation}`)).json();
    assert.equal(snapshot.reset, true);
    assert.deepEqual(snapshot.requests, []);
  });
});
