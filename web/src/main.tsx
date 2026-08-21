import { QueryClientProvider, QueryErrorResetBoundary } from "@tanstack/react-query";
import { RouterProvider } from "@tanstack/react-router";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { ErrorBoundary } from "react-error-boundary";
import "./index.css";
import { ApplicationError } from "@/components/states/application-error";
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

createRoot(rootElement).render(
  <StrictMode>
    <ThemeProvider>
      <QueryClientProvider client={queryClient}>
        <QueryErrorResetBoundary>
          {({ reset }) => (
            <ErrorBoundary
              fallbackRender={({ error, resetErrorBoundary }) => (
                <ApplicationError
                  title="MemoryOS stopped unexpectedly."
                  description="The application could not recover automatically. Your data was not changed."
                  error={error}
                  onRetry={resetErrorBoundary}
                />
              )}
              onReset={reset}
            >
              <RouterProvider router={router} />
            </ErrorBoundary>
          )}
        </QueryErrorResetBoundary>
      </QueryClientProvider>
    </ThemeProvider>
  </StrictMode>,
);
