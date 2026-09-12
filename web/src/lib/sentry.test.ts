import { beforeEach, describe, expect, it, vi } from "vitest";

const captureException = vi.fn();
const init = vi.fn();
const setContext = vi.fn();
const setTag = vi.fn();
const replayIntegration = vi.fn();

vi.mock("@sentry/react", () => ({
  captureException,
  init,
  replayIntegration,
  withScope: (
    callback: (scope: { setContext: typeof setContext; setTag: typeof setTag }) => void,
  ) => callback({ setContext, setTag }),
}));

const { captureReactRenderError, captureWorkflowFailure, initializeSentry } =
  await import("./sentry");

describe("Sentry browser configuration", () => {
  beforeEach(() => {
    captureException.mockReset();
    init.mockReset();
    replayIntegration.mockReset();
    setContext.mockReset();
    setTag.mockReset();
    window.__MEMORYOS_RUNTIME_CONFIG__ = {
      sentryDsn: "https://public@example.ingest.sentry.io/1",
      sentryEnvironment: "staging",
      sentryReplayEnabled: "true",
      release: "0123456789abcdef0123456789abcdef01234567",
    };
  });

  it("uses the runtime configuration and disables data-heavy telemetry", () => {
    initializeSentry();

    expect(init).toHaveBeenCalledWith(
      expect.objectContaining({
        dsn: "https://public@example.ingest.sentry.io/1",
        environment: "staging",
        release: "0123456789abcdef0123456789abcdef01234567",
        sendDefaultPii: false,
        tracesSampleRate: 0,
        replaysSessionSampleRate: 1,
        replaysOnErrorSampleRate: 1,
      }),
    );
    expect(replayIntegration).toHaveBeenCalledWith({
      maskAllText: true,
      maskAllInputs: true,
      blockAllMedia: true,
    });

    const options = init.mock.calls[0]?.[0];
    const event = {
      breadcrumbs: [{ message: "private navigation" }],
      request: { data: "private request" },
      user: { email: "private@example.com" },
    };
    expect(options.beforeBreadcrumb({ message: "private breadcrumb" })).toBeNull();
    expect(options.beforeSend(event)).toEqual({});
  });

  it("does not block application startup when telemetry initialization fails", () => {
    init.mockImplementationOnce(() => {
      throw new Error("telemetry unavailable");
    });

    expect(() => initializeSentry()).not.toThrow();
  });

  it("captures one React boundary error and retains its component stack", () => {
    const error = new Error("render failed");

    captureReactRenderError(error, "\n    at SearchPage");
    captureReactRenderError(error, "\n    at SearchPage");

    expect(captureException).toHaveBeenCalledOnce();
    expect(captureException).toHaveBeenCalledWith(error);
    expect(setContext).toHaveBeenCalledWith("react", { componentStack: "\n    at SearchPage" });
  });

  it("does not break the ErrorBoundary when telemetry capture fails", () => {
    captureException.mockImplementationOnce(() => {
      throw new Error("telemetry unavailable");
    });

    expect(() =>
      captureReactRenderError(new Error("render failed"), "\n    at SearchPage"),
    ).not.toThrow();
  });

  it("reports unexpected workflow failures with safe tags only", () => {
    const error = Object.assign(new Error("search unavailable"), { status: 503 });

    captureWorkflowFailure(error, {
      workflow: "search",
      stage: "request",
      failureKind: "api-unavailable",
    });

    expect(captureException).toHaveBeenCalledWith(error);
    expect(setTag).toHaveBeenCalledWith("workflow", "search");
    expect(setTag).toHaveBeenCalledWith("stage", "request");
    expect(setTag).toHaveBeenCalledWith("failure_kind", "api-unavailable");
    expect(setTag).toHaveBeenCalledWith("http_status", "503");
    expect(setContext).not.toHaveBeenCalled();
  });

  it("does not report expected authorization failures", () => {
    captureWorkflowFailure(Object.assign(new Error("forbidden"), { status: 403 }), {
      workflow: "file-source-upload",
      stage: "initiate",
      failureKind: "api-response",
    });

    expect(captureException).not.toHaveBeenCalled();
  });
});
