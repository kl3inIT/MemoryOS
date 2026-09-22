import { createFileRoute } from "@tanstack/react-router";
import { z } from "zod";

const uploads = z
  .union([z.string().uuid(), z.array(z.string().uuid()).max(20)])
  .transform((value) => (Array.isArray(value) ? value : [value]));

export const Route = createFileRoute("/_authenticated/_chat/chat/$sessionId")({
  /**
   * `ask` is a first question sent once into a new conversation, from an agent's detail view or from a file
   * in the library; `attach` names the uploads that question is about, attached before it is sent. A question
   * asked in the file preview may carry more than the file it was asked from.
   */
  validateSearch: (search: Record<string, unknown>): { ask?: string; attach?: string[] } => {
    const attached = uploads.safeParse(search.attach);
    return {
      ask: typeof search.ask === "string" && search.ask.trim() ? search.ask : undefined,
      attach: attached.success ? attached.data : undefined,
    };
  },
  component: () => null,
});
