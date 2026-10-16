import { seedChatProjects } from "../tests/fixtures/chat-server.ts";
import { mockProjects } from "../tests/fixtures/mock-workspace.ts";
import { createFixtureBackend } from "./fixture-backend.ts";

const host = "127.0.0.1";
const port = Number(process.env.MEMORYOS_MOCK_API_PORT ?? 8081);

// Sample Projects and Library files exist only here; `start-e2e-server.ts` serves neither.
seedChatProjects(mockProjects);
const backend = createFixtureBackend({ library: true });

await new Promise<void>((resolve, reject) => {
  backend.once("error", reject);
  backend.listen(port, host, () => resolve());
});

// noinspection HttpUrlsUsage
console.log(`Mock API listening on http://${host}:${port}`);
console.log(`Run the frontend against it with MEMORYOS_API_URL=http://${host}:${port} pnpm dev`);

const close = () => backend.close(() => process.exit(0));
process.once("SIGINT", close);
process.once("SIGTERM", close);
