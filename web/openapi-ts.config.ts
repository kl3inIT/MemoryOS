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
  },
  plugins: [
    "@hey-api/client-fetch",
    "@hey-api/typescript",
    "@hey-api/sdk",
    "@tanstack/react-query",
  ],
};
