export default {
  input: "../openapi.yml",
  output: "src/lib/hey-api",
  parser: {
    hooks: {
      operations: {
        // SSE uses the generated SDK stream, not a paginated TanStack query.
        isQuery: ({ path }: { path: string }) =>
          path === "/api/chat/sessions/{sessionId}/messages/{assistantMessageId}/events"
            ? false
            : undefined,
      },
    },
    patch: {
      // The same-origin guard header is added to every unsafe request by the client
      // interceptor in src/lib/api.ts, so call sites do not pass it.
      operations: (_method: string, _path: string, operation: { parameters?: unknown[] }) => {
        operation.parameters = operation.parameters?.filter(
          (parameter) =>
            !(
              typeof parameter === "object" &&
              parameter !== null &&
              "in" in parameter &&
              "name" in parameter &&
              parameter.in === "header" &&
              parameter.name === "X-MemoryOS-CSRF"
            ),
        );
      },
    },
  },
  plugins: [
    // Every SDK call rejects with ApiError on a non-2xx response (see src/lib/api.ts).
    { name: "@hey-api/client-fetch", throwOnError: true },
    "@hey-api/typescript",
    "@hey-api/sdk",
    "@tanstack/react-query",
    // Schemas for parsing responses; forms and stream payloads build on them where OpenAPI describes the shape.
    "zod",
  ],
};
