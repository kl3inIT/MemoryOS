import { createFileRoute } from "@tanstack/react-router";
import { z } from "zod";

export const Route = createFileRoute("/_authenticated/_chat/assistants/$personaId/new")({
  /**
   * A new topic with the assistant, written down by its first send. `ask` is a first question sent once, from the
   * assistant's detail view.
   */
  validateSearch: z.object({
    ask: z
      .string()
      .refine((question) => question.trim() !== "")
      .optional()
      .catch(undefined),
  }),
  component: () => null,
});
