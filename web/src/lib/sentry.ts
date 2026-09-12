import * as Sentry from "@sentry/react";
import type { ErrorInfo } from "react";

const capturedReactErrors = new WeakSet<object>();

interface RuntimeConfiguration {
  sentryDsn?: string;
  sentryEnvironment?: string;
  sentryReplayEnabled?: string;
  release?: string;
}

export type WorkflowFailure = {
  workflow: "file-source-upload" | "google-drive-sync" | "indexing" | "search";
  stage: string;
  failureKind: string;
  httpStatus?: number;
};

declare global {
  interface Window {
    __MEMORYOS_RUNTIME_CONFIG__?: RuntimeConfiguration;
  }
}

export function initializeSentry() {
  const configuration = sentryConfiguration();

  if (!configuration.dsn) return;

  try {
    Sentry.init({
      dsn: configuration.dsn,
      environment: configuration.environment,
      release: configuration.release,
      sendDefaultPii: false,
      tracesSampleRate: 0,
      integrations: configuration.replayEnabled
        ? [
            Sentry.replayIntegration({
              maskAllText: true,
              maskAllInputs: true,
              blockAllMedia: true,
            }),
          ]
        : [],
      replaysSessionSampleRate: configuration.replayEnabled ? 1 : 0,
      replaysOnErrorSampleRate: configuration.replayEnabled ? 1 : 0,
      beforeBreadcrumb: () => null,
      beforeSend(event) {
        delete event.breadcrumbs;
        delete event.request;
        delete event.user;
        return event;
      },
    });
  } catch {
    // Telemetry is optional and must never prevent the application from rendering.
  }
}

function sentryConfiguration() {
  const runtime = window.__MEMORYOS_RUNTIME_CONFIG__;
  return {
    dsn: nonBlank(runtime?.sentryDsn) ?? nonBlank(import.meta.env.VITE_MEMORYOS_SENTRY_DSN),
    environment:
      nonBlank(runtime?.sentryEnvironment) ??
      nonBlank(import.meta.env.VITE_MEMORYOS_SENTRY_ENVIRONMENT) ??
      "local",
    release:
      nonBlank(runtime?.release) ?? nonBlank(import.meta.env.VITE_MEMORYOS_RELEASE) ?? "local",
    replayEnabled:
      enabled(runtime?.sentryReplayEnabled) ??
      enabled(import.meta.env.VITE_MEMORYOS_SENTRY_REPLAY_ENABLED) ??
      false,
  };
}

function nonBlank(value: string | undefined) {
  const trimmed = value?.trim();
  return trimmed ? trimmed : undefined;
}

function enabled(value: string | undefined) {
  const normalized = value?.trim().toLowerCase();
  if (normalized === "true") return true;
  if (normalized === "false") return false;
  return undefined;
}

export function captureReactRenderError(
  error: unknown,
  componentStack: ErrorInfo["componentStack"],
) {
  if (error !== null && typeof error === "object") {
    if (capturedReactErrors.has(error)) return;

    capturedReactErrors.add(error);
  }

  try {
    Sentry.withScope((scope) => {
      scope.setContext("react", { componentStack });
      Sentry.captureException(error);
    });
  } catch {
    // Preserve the ErrorBoundary fallback even when telemetry is unavailable.
  }
}

/** Reports an unexpected handled failure without including product data. */
export function captureWorkflowFailure(error: unknown, failure: WorkflowFailure) {
  const httpStatus = failure.httpStatus ?? statusOf(error);
  if (!shouldReport(httpStatus)) return;

  try {
    Sentry.withScope((scope) => {
      scope.setTag("workflow", failure.workflow);
      scope.setTag("stage", failure.stage);
      scope.setTag("failure_kind", failure.failureKind);
      if (httpStatus !== undefined) scope.setTag("http_status", String(httpStatus));
      Sentry.captureException(error);
    });
  } catch {
    // Telemetry must not alter a completed UI error path.
  }
}

function statusOf(error: unknown): number | undefined {
  if (!error || typeof error !== "object" || !("status" in error)) return undefined;
  const status = error.status;
  return typeof status === "number" && Number.isInteger(status) ? status : undefined;
}

function shouldReport(status: number | undefined) {
  return status === undefined || status === 0 || status === 408 || status === 429 || status >= 500;
}
