import { setupServer } from "msw/node";

/**
 * The HTTP boundary of unit tests. Tests declare responses per operation with the generated
 * `handle<Operation>` factories from `@/lib/hey-api/msw.gen`, for example
 * `server.use(handleListDocumentSets({ body: [view] }))`. A request no handler answers is logged as an
 * error and rejects, so the code under test sees a network failure.
 */
export const server = setupServer();
