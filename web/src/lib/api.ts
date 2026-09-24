import { client } from "./hey-api/client.gen";

/** The same-origin guard the API requires on every unsafe browser-session request. */
export const sameOriginMutationHeaders = { "X-MemoryOS-CSRF": "1" as const };
const safeMethods = new Set(["GET", "HEAD", "OPTIONS"]);

export class ApiError extends Error {
  readonly status: number | undefined;

  constructor(status: number | undefined, cause: unknown) {
    super(status ? `MemoryOS API returned ${status}` : "MemoryOS API request failed", { cause });
    this.name = "ApiError";
    this.status = status;
  }
}

client.setConfig({
  baseUrl: window.location.origin,
  credentials: "same-origin",
  // Every SDK call rejects with ApiError on a non-2xx response; the generator emits the same default.
  throwOnError: true,
});

client.interceptors.request.use((request) => {
  if (!safeMethods.has(request.method)) {
    for (const [name, value] of Object.entries(sameOriginMutationHeaders)) {
      request.headers.set(name, value);
    }
  }
  return request;
});

client.interceptors.error.use((error, response) => new ApiError(response?.status, error));

export function isUnauthenticated(error: unknown): error is ApiError {
  return error instanceof ApiError && error.status === 401;
}

export function problemCode(error: ApiError) {
  const cause = error.cause;
  if (!cause || typeof cause !== "object" || !("code" in cause)) return undefined;
  const code = cause.code;
  return typeof code === "string" ? code : undefined;
}
