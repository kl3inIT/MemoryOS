import { createFileRoute } from "@tanstack/react-router";

export const Route = createFileRoute("/_authenticated/_chat/")({
  // A page may hand a library file to a new conversation; the chat page attaches it and clears the parameter.
  // The key stays optional so every other `navigate({ to: "/" })` in the application keeps working unchanged.
  validateSearch: (search: Record<string, unknown>): { attach?: string } =>
    typeof search.attach === "string" ? { attach: search.attach } : {},
  component: () => null,
});
