import { readFileSync } from "node:fs";
import { Ajv2020, type ErrorObject, type ValidateFunction } from "ajv/dist/2020.js";
import { parse } from "yaml";

/** Header fields as sent, in order. */
export type Headers = [name: string, value: string][];
export type JsonObject = Record<string, unknown>;

/** What a request contains, read into the fields of the JSON batch format, and every way it breaks the spec. */
export interface InspectedRequest {
  /** `json` for a JSON body, sent in both the JSON batch and JSON event formats. */
  format: "json" | "queryString";
  session: JsonObject | null;
  events: JsonObject[];
  /** When the device says it sent the request, or `null` if it's missing or not a string. */
  sentAt: string | null;
  /** Query items already in the endpoint, before Davy's own, in the query string format. */
  endpointQuery: [name: string, value: string][];
  problems: string[];
}

interface Parameter {
  name: string;
  location: string;
  required: boolean;
  isJson: boolean;
  validate: ValidateFunction;
}

type Method = "GET" | "POST";
type Path = (string | number)[];

const SPEC_URI = "urn:davy:openapi";
const UNRESERVED_OR_ESCAPE = /^[A-Za-z0-9\-._~%]*$/;
const BAD_ESCAPE = /%(?![0-9A-Fa-f]{2})/;
const STRING_LITERAL = /"(?:[^"\\]|\\.)*"/g;

// How query string parameters map to the fields of the JSON batch format.
const SESSION_PARAMETERS: Record<string, string> = {
  sessionId: "id",
  environment: "environment",
  appVersion: "appVersion",
  build: "build",
  os: "os",
  osVersion: "osVersion",
  deviceModel: "deviceModel",
  locale: "locale",
};
const EVENT_PARAMETERS: Record<string, string> = {
  eventId: "id",
  type: "type",
  name: "name",
  timestamp: "timestamp",
  info: "info",
};
const SENT_AT_PARAMETER = "sentAt";

/** The parts of `Spec/openapi.yaml` the server checks requests against. */
export class Spec {
  readonly batch: ValidateFunction;
  readonly queryParameters: Record<Method, Parameter[]>;
  readonly queryNames: Set<string>;
  readonly #document: unknown;
  readonly #ajv: Ajv2020;

  constructor(path: string) {
    this.#document = parse(readFileSync(path, "utf8"));
    // The spec uses `pattern` for formats that must be checked, since `format` is only an annotation.
    this.#ajv = new Ajv2020({ allErrors: true, strict: false, validateFormats: false, validateSchema: false, verbose: true });
    this.#ajv.addSchema(this.#document as object, SPEC_URI);
    this.batch = this.#validator(["paths", "/events", "post", "requestBody", "content", "application/json", "schema"]);
    this.queryParameters = {
      GET: this.#parameters("/events/{name}", "get"),
      POST: this.#parameters("/events/{name}", "post"),
    };
    this.queryNames = new Set(this.queryParameters.POST.filter((p) => p.location === "query").map((p) => p.name));
  }

  // The document is untyped YAML.
  #at(path: Path): any {
    return path.reduce<any>((node, token) => node?.[token], this.#document);
  }

  /** The object at `path`, following any `$ref`, and the path it was found at. */
  #follow(path: Path): [any, Path] {
    let node = this.#at(path);
    while (typeof node?.$ref === "string") {
      path = node.$ref.replace(/^#\//, "").split("/").map((t: string) => t.replaceAll("~1", "/").replaceAll("~0", "~"));
      node = this.#at(path);
    }
    return [node, path];
  }

  #validator(path: Path): ValidateFunction {
    // Referencing the schema by its location in the document lets its own `$ref`s resolve.
    const pointer = path.map((t) => "/" + String(t).replaceAll("~", "~0").replaceAll("/", "~1")).join("");
    return this.#ajv.compile({ $ref: `${SPEC_URI}#${pointer}` });
  }

  #parameters(template: string, method: string): Parameter[] {
    const item = ["paths", template];
    const operation = [...item, method];
    const references: Path[] = [
      ...(this.#at(item).parameters ?? []).map((_: unknown, i: number) => [...item, "parameters", i]),
      ...(this.#at(operation).parameters ?? []).map((_: unknown, i: number) => [...operation, "parameters", i]),
    ];
    return references.map((reference) => {
      const [parameter, path] = this.#follow(reference);
      const isJson = !("schema" in parameter);
      return {
        name: parameter.name,
        location: parameter.in,
        required: parameter.required === true,
        isJson,
        validate: this.#validator([...path, ...(isJson ? ["content", "application/json", "schema"] : ["schema"])]),
      };
    });
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

function decodeLeniently(encoded: string): string {
  try {
    return decodeURIComponent(encoded);
  } catch {
    return encoded;
  }
}

/** Percent-decodes `encoded`, noting if it isn't encoded the way Davy encodes everything. */
function decode(encoded: string, label: string, problems: string[]): string {
  if (!UNRESERVED_OR_ESCAPE.test(encoded) || BAD_ESCAPE.test(encoded)) {
    problems.push(`${label} isn't percent-encoded except for A–Z a–z 0–9 - . _ ~: ${encoded}`);
    return decodeLeniently(encoded);
  }
  try {
    return decodeURIComponent(encoded);
  } catch {
    problems.push(`${label} isn't UTF-8 once percent-decoded: ${encoded}`);
    return encoded;
  }
}

/** The keys of `value` in the order `text` has them, since `Object.keys` puts integer-like keys first. */
function keysInOrder(text: string, value: JsonObject): string[] {
  const keys = Object.keys(value);
  const strings = [...text.matchAll(STRING_LITERAL)].map((match) => JSON.parse(match[0]) as string);
  // In an object of strings, the string literals alternate between keys and values.
  const allStrings = Object.values(value).every((v) => typeof v === "string");
  return allStrings && strings.length === keys.length * 2 ? strings.filter((_, i) => i % 2 === 0) : keys;
}

/** Checks the spec's rule that JSON in a URL is compact, with sorted keys and unescaped slashes. */
function jsonTextProblems(text: string, value: unknown, label: string): string[] {
  const problems: string[] = [];
  if (/\s/.test(text.replace(STRING_LITERAL, ""))) problems.push(`${label} isn't compact JSON: ${text}`);
  if (text.includes("\\/")) problems.push(`${label} escapes slashes: ${text}`);
  if (isObject(value)) {
    const keys = keysInOrder(text, value);
    const sorted = [...keys].sort();
    if (keys.some((key, i) => key !== sorted[i])) problems.push(`${label}'s keys aren't sorted: ${keys.join(", ")}`);
  }
  return problems;
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

function inspectQueryString(
  spec: Spec,
  method: Method,
  path: string,
  query: string,
  body: Buffer,
  problems: string[],
): [JsonObject, JsonObject[], [string, string][], string | null] {
  if (body.length > 0) problems.push("The request has a body, but query string requests have none");
  // Split by hand rather than with `URLSearchParams`, so the encoding and order can be checked.
  const items = query
    .split("&")
    .filter(Boolean)
    .map((item): [string, string] => {
      const separator = item.indexOf("=");
      return separator === -1 ? [decodeLeniently(item), ""] : [decodeLeniently(item.slice(0, separator)), item.slice(separator + 1)];
    });
  // Davy's parameters come after any query items already in the endpoint.
  let start = items.findIndex(([name]) => spec.queryNames.has(name));
  if (start === -1) start = items.length;
  const endpointItems = items.slice(0, start).map(([name, value]): [string, string] => [name, decodeLeniently(value)]);
  const sent = items.slice(start);

  const values: Record<string, unknown> = {};
  for (const parameter of spec.queryParameters[method]) {
    const label = `The ${parameter.location} parameter ${parameter.name}`;
    let encoded: string;
    if (parameter.location === "path") {
      encoded = path.slice(path.lastIndexOf("/") + 1);
    } else {
      const matching = sent.filter(([name]) => name === parameter.name).map(([, value]) => value);
      if (matching.length > 1) problems.push(`${label} is sent ${matching.length} times`);
      if (matching.length === 0) {
        if (parameter.required) problems.push(`${label} is required but missing`);
        continue;
      }
      encoded = matching[0];
    }
    const value = decode(encoded, label, problems);
    if (!parameter.isJson) {
      problems.push(...schemaProblems(parameter.validate, value, label));
      values[parameter.name] = value;
      continue;
    }
    try {
      const parsed: unknown = JSON.parse(value);
      problems.push(...jsonTextProblems(value, parsed, label), ...schemaProblems(parameter.validate, parsed, label));
      values[parameter.name] = parsed;
    } catch {
      problems.push(`${label} isn't valid JSON: ${value}`);
      values[parameter.name] = value;
    }
  }

  const specOrder = spec.queryParameters[method].filter((p) => p.location === "query").map((p) => p.name);
  const sentNames = sent.map(([name]) => name);
  for (const name of sentNames) {
    if (!specOrder.includes(name)) problems.push(`The query parameter ${name} isn't in the spec`);
  }
  const sentOrder = sentNames.filter((name) => specOrder.includes(name));
  if (sentOrder.join("&") !== specOrder.filter((name) => sentNames.includes(name)).join("&")) {
    problems.push(`Query parameters are sent in the order ${sentOrder.join(", ")}, not the spec's`);
  }

  const pick = (fields: Record<string, string>) =>
    Object.fromEntries(Object.entries(fields).filter(([name]) => name in values).map(([name, field]) => [field, values[name]]));
  return [pick(SESSION_PARAMETERS), [pick(EVENT_PARAMETERS)], endpointItems, typeof values[SENT_AT_PARAMETER] === "string" ? values[SENT_AT_PARAMETER] : null];
}

/** What a request contains and every way it breaks the spec, or `null` if it isn't from Davy. */
export function inspectRequest(spec: Spec, method: string, target: string, headers: Headers, body: Buffer): InspectedRequest | null {
  const separator = target.indexOf("?");
  const path = separator === -1 ? target : target.slice(0, separator);
  const query = separator === -1 ? "" : target.slice(separator + 1);
  const hasEventParameters = query.split("&").some((item) => spec.queryNames.has(decodeLeniently(item.split("=")[0])));

  let format: InspectedRequest["format"];
  if (method === "POST" && (body.length > 0 || !hasEventParameters)) format = "json";
  else if ((method === "GET" || method === "POST") && hasEventParameters) format = "queryString";
  else return null;

  const problems: string[] = [];
  let session: JsonObject | null;
  let events: JsonObject[];
  let sentAt: string | null;
  let endpointQuery: [string, string][] = [];
  if (format === "json") {
    [session, events, sentAt] = inspectBatch(spec, headers, body, problems);
  } else {
    [session, events, endpointQuery, sentAt] = inspectQueryString(spec, method as Method, path, query, body, problems);
  }
  if (header(headers, "Cookie") !== undefined) problems.push("The request sends cookies, which Davy never does");
  return { format, session, events, sentAt, endpointQuery, problems };
}
