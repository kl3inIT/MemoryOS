import { client } from "./hey-api/client.gen";
export const sameOriginMutationHeaders = { "X-MemoryOS-CSRF": "1" as const };

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
