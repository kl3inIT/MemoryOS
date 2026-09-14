import { QueryClientProvider, QueryErrorResetBoundary } from "@tanstack/react-query";
import { RouterProvider } from "@tanstack/react-router";
import { StrictMode } from "react";
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

createRoot(rootElement).render(
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
