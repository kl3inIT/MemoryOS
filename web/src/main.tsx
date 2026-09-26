import { QueryClientProvider, QueryErrorResetBoundary } from "@tanstack/react-query";
import { isNotFound, isRedirect, RouterProvider } from "@tanstack/react-router";
import { StrictMode, type ErrorInfo } from "react";
import { createRoot } from "react-dom/client";
import "./index.css";
import "@/i18n";
import { ApplicationErrorBoundary } from "@/components/states/application-error-boundary";
import { ThemeProvider } from "@/features/theme/theme-provider";
import "@/lib/api";
import { setupPreloadErrorReloadHandler } from "@/lib/preload-error-reload";
import { queryClient } from "@/lib/query-client";
import { captureReactRenderError, initializeSentry } from "@/lib/sentry";
import { router } from "@/router";

initializeSentry();
setupPreloadErrorReloadHandler();

const rootElement = document.getElementById("root");

if (!rootElement) {
  throw new Error("MemoryOS root element is missing");
}

/**
 * Route error components catch render and loader failures before the application boundary sees them, so every
 * error React catches or does not is reported here; the report is made once per error, whichever boundary also
 * reports it. A not-found or a redirect is the router's navigation, not a failure.
 */
function reportReactError(error: unknown, errorInfo: ErrorInfo) {
  if (isNotFound(error) || isRedirect(error)) return;
  captureReactRenderError(error, errorInfo.componentStack);
  if (import.meta.env.DEV) console.error(error);
}

createRoot(rootElement, {
  onCaughtError: reportReactError,
  onUncaughtError: reportReactError,
}).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <QueryErrorResetBoundary>
        {({ reset }) => (
          <ApplicationErrorBoundary onReset={reset} onError={captureReactRenderError}>
            <ThemeProvider>
              <RouterProvider router={router} />
            </ThemeProvider>
          </ApplicationErrorBoundary>
        )}
      </QueryErrorResetBoundary>
    </QueryClientProvider>
  </StrictMode>,
);
