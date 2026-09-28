import { useAppTranslation } from "@/i18n/use-app-translation";
import type { ReactNode } from "react";
import { ErrorBoundary } from "react-error-boundary";
import { ApplicationError } from "@/components/states/application-error";

interface ApplicationErrorBoundaryProps {
  children: ReactNode;
  onReset: () => void;
}

export function ApplicationErrorBoundary({ children, onReset }: ApplicationErrorBoundaryProps) {
  const ui = useAppTranslation();

  return (
    <ErrorBoundary
      fallbackRender={({ error, resetErrorBoundary }) => (
        <ApplicationError
          title={ui("MemoryOS stopped unexpectedly.")}
          description={ui(
            "The application could not recover automatically. Your data was not changed.",
          )}
          error={error}
          onRetry={resetErrorBoundary}
        />
      )}
      onReset={onReset}
    >
      {children}
    </ErrorBoundary>
  );
}
