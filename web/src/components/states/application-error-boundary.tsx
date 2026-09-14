import { useAppTranslation } from "@/i18n/use-app-translation";
import type { ErrorInfo, ReactNode } from "react";
import { ErrorBoundary } from "react-error-boundary";
import { ApplicationError } from "@/components/states/application-error";

interface ApplicationErrorBoundaryProps {
  children: ReactNode;
  onError: (error: unknown, componentStack: ErrorInfo["componentStack"]) => void;
  onReset: () => void;
}

export function ApplicationErrorBoundary({
  children,
  onError,
  onReset,
}: ApplicationErrorBoundaryProps) {
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
      onError={(error, info) => onError(error, info.componentStack)}
      onReset={onReset}
    >
      {children}
    </ErrorBoundary>
  );
}
