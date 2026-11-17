import { readFileSync } from "node:fs";
import { Ajv2020, type ErrorObject, type ValidateFunction } from "ajv/dist/2020.js";
import { parse } from "yaml";

/** Header fields as sent, in order. */
export type Headers = [name: string, value: string][];
export type JsonObject = Record<string, unknown>;

/** What a request contains, and every way it breaks the spec. */
export interface InspectedRequest {
  session: JsonObject | null;
  events: JsonObject[];
  /** When the device says it sent the request, or `null` if it's missing or not a string. */
  sentAt: string | null;
  problems: string[];
}

type Path = (string | number)[];

const SPEC_URI = "urn:davy:openapi";

/** The parts of `Spec/openapi.yaml` the server checks requests against. */
export class Spec {
  readonly batch: ValidateFunction;
  readonly #ajv: Ajv2020;

  constructor(path: string) {
    const document = parse(readFileSync(path, "utf8"));
    // The spec uses `pattern` for formats that must be checked, since `format` is only an annotation.
    this.#ajv = new Ajv2020({ allErrors: true, strict: false, validateFormats: false, validateSchema: false, verbose: true });
    this.#ajv.addSchema(document as object, SPEC_URI);
    this.batch = this.#validator(["paths", "/events", "post", "requestBody", "content", "application/json", "schema"]);
  }

  #validator(path: Path): ValidateFunction {
    // Referencing the schema by its location in the document lets its own `$ref`s resolve.
    const pointer = path.map((t) => "/" + String(t).replaceAll("~", "~0").replaceAll("/", "~1")).join("");
    return this.#ajv.compile({ $ref: `${SPEC_URI}#${pointer}` });
  }
}

function isObject(value: unknown): value is JsonObject {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function describe(error: ErrorObject): string {
  const value = typeof error.data === "object" && error.data !== null ? "" : `: ${JSON.stringify(error.data)}`;
  switch (error.keyword) {
    case "additionalProperties":
      return `has a property that isn't in the spec: ${error.params.additionalProperty}`;
    case "required":
      return `is missing the required property ${error.params.missingProperty}`;
    case "enum":
      return `isn't one of ${error.params.allowedValues.join(", ")}${value}`;
    case "pattern":
      return `doesn't match ${error.params.pattern}${value}`;
    default:
      return `${error.message}${value}`;
  }
}

function schemaProblems(validate: ValidateFunction, value: unknown, label: string): string[] {
  if (validate(value)) return [];
  return (validate.errors ?? []).map((error) => {
    const tokens = error.instancePath.split("/").slice(1).map((t) => t.replaceAll("~1", "/").replaceAll("~0", "~"));
    const location = label + tokens.map((t) => (/^\d+$/.test(t) ? `[${t}]` : `.${t}`)).join("");
    return `${location} ${describe(error)}`;
  });
}

export function header(headers: Headers, name: string): string | undefined {
  return headers.find(([key]) => key.toLowerCase() === name.toLowerCase())?.[1];
}

function inspectBatch(
  spec: Spec,
  headers: Headers,
  body: Buffer,
  problems: string[],
): [JsonObject | null, JsonObject[], string | null] {
  const contentType = (header(headers, "Content-Type") ?? "").split(";")[0].trim();
  if (contentType.toLowerCase() !== "application/json") {
    problems.push(`The Content-Type is "${contentType}", not "application/json"`);
  }
  if (body.length === 0) {
    problems.push("The request has no body, but a JSON batch requires one");
    return [null, [], null];
  }
  let payload: unknown;
  try {
    payload = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(body));
  } catch {
    problems.push("The body isn't valid UTF-8 JSON");
    return [null, [], null];
  }
  problems.push(...schemaProblems(spec.batch, payload, "body"));
  if (!isObject(payload)) return [null, [], null];
  const session = isObject(payload.session) ? payload.session : null;
  const events = Array.isArray(payload.events) ? payload.events.filter(isObject) : [];
  return [session, events, typeof payload.sentAt === "string" ? payload.sentAt : null];
}

/** What a request contains and every way it breaks the spec, or `null` if it isn't from Davy. */
export function inspectRequest(spec: Spec, method: string, _target: string, headers: Headers, body: Buffer): InspectedRequest | null {
  if (method !== "POST") return null;

  const problems: string[] = [];
  const [session, events, sentAt] = inspectBatch(spec, headers, body, problems);
  if (header(headers, "Cookie") !== undefined) problems.push("The request sends cookies, which Davy never does");
  return { session, events, sentAt, problems };
}
