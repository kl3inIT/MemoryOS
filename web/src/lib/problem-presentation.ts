import { ApiError, problemCode } from "./api";
import type { en } from "@/i18n/en";

export type ErrorKey = keyof typeof en.errors;
export type ProblemKind =
  | "unauthenticated"
  | "forbidden"
  | "validation"
  | "conflict"
  | "notFound"
  | "throttled"
  | "unavailable"
  | "network"
  | "unexpected";
export type ErrorMessage = { key: ErrorKey; params?: Record<string, number> };
export type ProblemPresentation = {
  kind: ProblemKind;
  message: ErrorMessage;
  code?: string;
  placement: "session" | "page" | "inline" | "notification";
  recovery:
    | "signIn"
    | "refreshIdentity"
    | "correct"
    | "reload"
    | "retryRead"
    | "reconcile"
    | "none";
  fields: Record<string, ErrorMessage>;
  preserveData: boolean;
};
type Context = "initialLoad" | "backgroundRead" | "mutation";
const fieldCodes: Record<string, ErrorKey> = {
  REQUIRED: "required",
  INVALID: "invalid",
  EMAIL: "email",
  SIZE: "size",
  MIN: "min",
  MAX: "max",
};
const safeCode = (code: unknown): code is string =>
  typeof code === "string" && /^[A-Z][A-Z0-9_]{0,80}$/.test(code);

/** No raw server text and no side effects. The operation owns placement and actual retry semantics. */
export function presentProblem(
  error: unknown,
  context: Context,
  messages: Readonly<Record<string, ErrorMessage>> = {},
): ProblemPresentation {
  const status = error instanceof ApiError ? error.status : undefined;
  const rawCode = error instanceof ApiError ? problemCode(error) : undefined;
  const code = safeCode(rawCode) ? rawCode : undefined;
  let kind: ProblemKind;
  switch (status) {
    case 401:
      kind = "unauthenticated";
      break;
    case 403:
      kind = "forbidden";
      break;
    case 400:
    case 422:
      kind = "validation";
      break;
    case 409:
    case 412:
    case 428:
      kind = "conflict";
      break;
    case 404:
    case 410:
      kind = "notFound";
      break;
    case 429:
      kind = "throttled";
      break;
    case 502:
    case 503:
    case 504:
      kind = "unavailable";
      break;
    default:
      kind =
        error instanceof TypeError || (error instanceof ApiError && status === undefined)
          ? "network"
          : "unexpected";
  }
  const fields: Record<string, ErrorMessage> = Object.create(null);
  const body = error instanceof ApiError ? error.cause : undefined;
  if (
    kind === "validation" &&
    body &&
    typeof body === "object" &&
    "errors" in body &&
    Array.isArray(body.errors)
  ) {
    for (const entry of body.errors.slice(0, 100)) {
      if (
        !entry ||
        typeof entry !== "object" ||
        typeof entry.field !== "string" ||
        !/^[a-zA-Z][\w.[\]-]{0,127}$/.test(entry.field)
      )
        continue;
      const key =
        typeof entry.code === "string" && Object.hasOwn(fieldCodes, entry.code)
          ? fieldCodes[entry.code]
          : "invalid";
      const params: Record<string, number> = {};
      for (const name of ["min", "max"] as const) {
        const value = entry.params?.[name];
        if (typeof value === "number" && Number.isFinite(value) && value >= 0) params[name] = value;
      }
      const complete =
        key === "size"
          ? params.min !== undefined && params.max !== undefined
          : key === "min" || key === "max"
            ? params[key] !== undefined
            : true;
      fields[entry.field] = { key: complete ? key : "invalid", params };
    }
  }
  const auth = kind === "unauthenticated" || kind === "forbidden";
  return {
    kind,
    code,
    message: code && Object.hasOwn(messages, code) ? messages[code] : { key: kind },
    fields,
    placement:
      kind === "unauthenticated"
        ? "session"
        : context === "mutation"
          ? "inline"
          : context === "backgroundRead" && !auth
            ? "notification"
            : "page",
    recovery:
      kind === "unauthenticated"
        ? "signIn"
        : kind === "forbidden"
          ? "refreshIdentity"
          : kind === "validation"
            ? "correct"
            : kind === "conflict"
              ? "reload"
              : kind === "notFound"
                ? "none"
                : context === "mutation"
                  ? "reconcile"
                  : "retryRead",
    preserveData: !auth && context !== "initialLoad",
  };
}
