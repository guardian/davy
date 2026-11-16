import { existsSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { createTestServer, Recorder } from "./server.ts";
import { Spec } from "./spec.ts";

const specPath = process.env.DAVY_SPEC ?? fileURLToPath(new URL("../../Spec/openapi.yaml", import.meta.url));
const host = process.env.HOST ?? "0.0.0.0";
const port = Number(process.env.PORT ?? 8080);
const maxRequests = Number(process.env.MAX_REQUESTS ?? 10_000);

if (!existsSync(specPath)) {
  console.error(`Can't find the spec at ${specPath}. In Docker, mount the repository's Spec folder at /app/Spec.`);
  process.exit(1);
}

const server = createTestServer(new Spec(specPath), new Recorder(maxRequests), { log: (line) => console.log(line) });
server.listen(port, host, () => {
  console.log(`Davy test server, checking requests against ${specPath}`);
  console.log(`  Events page:       http://localhost:${port}/`);
  console.log(`  Endpoint:          http://localhost:${port}/events`);
  console.log(`  Android:           run \`adb reverse tcp:${port} tcp:${port}\`, then use http://localhost:${port}/events`);
});

// As a container's main process, Node would otherwise ignore these signals.
for (const signal of ["SIGINT", "SIGTERM"] as const) {
  process.on(signal, () => process.exit(0));
}
