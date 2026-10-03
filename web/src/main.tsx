import { QueryClientProvider, QueryErrorResetBoundary } from "@tanstack/react-query";
import { isNotFound, isRedirect, RouterProvider } from "@tanstack/react-router";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import "./index.css";
import "@/i18n";
import { ApplicationErrorBoundary } from "@/components/states/application-error-boundary";
import { ThemeProvider } from "@/features/theme/theme-provider";
import "@/lib/api";
import { setupPreloadErrorReloadHandler } from "@/lib/preload-error-reload";
import { queryClient } from "@/lib/query-client";
import { router } from "@/router";

setupPreloadErrorReloadHandler();

const rootElement = document.getElementById("root");

if (!rootElement) {
  throw new Error("MemoryOS root element is missing");
}

/**
 * Route error components and the application boundary show the failure; the root handlers only log it during
 * development. Browser error monitoring is absent until MEM-200 replaces it, so nothing leaves the browser.
 * A not-found or a redirect is the router's navigation, not a failure.
 */
function logReactError(error: unknown) {
  if (isNotFound(error) || isRedirect(error)) return;
  if (import.meta.env.DEV) console.error(error);
}

createRoot(rootElement, {
  onCaughtError: logReactError,
  onUncaughtError: logReactError,
}).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <QueryErrorResetBoundary>
        {({ reset }) => (
          <ApplicationErrorBoundary onReset={reset}>
            <ThemeProvider>
              <RouterProvider router={router} />
            </ThemeProvider>
          </ApplicationErrorBoundary>
        )}
      </QueryErrorResetBoundary>
    </QueryClientProvider>
  </StrictMode>,
);
