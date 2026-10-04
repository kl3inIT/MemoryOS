import type { QueryClient } from "@tanstack/react-query";
import type { ProviderMark } from "@/components/provider-logos/provider-marks";
import {
  listChatModelFlowsQueryKey,
  listSystemOneConnectionsQueryKey,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { SystemOneConnection, SystemOneType } from "@/lib/hey-api/types.gen";
import { presentProblem, type ErrorMessage } from "@/lib/problem-presentation";

export type SystemOneProviderId = SystemOneType["provider"];

/** The task this page configures: the check of a question before Chat answers it. */
export const CHECK_FLOW = "CHAT_GUARDRAIL";

/** Product names stay untranslated. */
export const providerNames: Record<SystemOneProviderId, string> = {
  TYPESAFE: "TypeSafe",
  CLOUDFLARE: "Cloudflare",
  NINEROUTER: "9Router",
  LAYA: "Laya",
  SYSTEMONE_COMPATIBLE: "System One compatible",
};

/** The brand mark of a type; the compatible protocol has none and shows a neutral icon. */
export const providerMark: Partial<Record<SystemOneProviderId, ProviderMark>> = {
  TYPESAFE: "TYPESAFE",
  CLOUDFLARE: "CLOUDFLARE",
  NINEROUTER: "NINEROUTER",
  LAYA: "LAYA",
};

export const endpointPlaceholder: Partial<Record<SystemOneProviderId, string>> = {
  NINEROUTER: "http://localhost:20128/v1",
  LAYA: "http://localhost:8000/v1",
  SYSTEMONE_COMPATIBLE: "http://10.0.0.5:8080/v1",
  CLOUDFLARE: "3f9a1c0b7d2e4a56b8c9d0e1f2a3b4c5",
};

export const modelPlaceholder: Partial<Record<SystemOneProviderId, string>> = {
  NINEROUTER: "openrouter/typesafe/jev-1.13",
  SYSTEMONE_COMPATIBLE: "clef-flash",
};

/** Refreshes what a System One change affects: the connections and what the task runs on. */
export function invalidateSystemOne(cache: QueryClient) {
  return Promise.all([
    cache.invalidateQueries({ queryKey: listSystemOneConnectionsQueryKey() }),
    cache.invalidateQueries({ queryKey: listChatModelFlowsQueryKey() }),
  ]);
}

export const systemOneProblem = (error: unknown): ErrorMessage =>
  presentProblem(error, "mutation", {
    CHAT_PROVIDER_UNAVAILABLE: { key: "systemOneUnavailable" },
  }).message;

/** A delete is refused only while the task runs on the connection. */
export const systemOneDeleteProblem = (error: unknown): ErrorMessage =>
  presentProblem(error, "mutation", { CHAT_CONFLICT: { key: "systemOneInUse" } }).message;

/** Where a connection points, as its card shows it: the host of an address, or Workers AI for an account. */
export function connectionTarget(connection: SystemOneConnection) {
  if (connection.provider === "CLOUDFLARE") return "Workers AI";
  if (connection.provider === "TYPESAFE") return "api.typesafe.ai";
  try {
    return new URL(connection.endpoint).host;
  } catch {
    return connection.endpoint;
  }
}
