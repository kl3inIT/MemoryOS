import { createFileRoute } from "@tanstack/react-router";
import { z } from "zod";

const uuid = z.string().uuid();

export const Route = createFileRoute("/_authenticated/_chat/chat/$sessionId")({
  /**
   * `ask` is a first question sent once into a new conversation, from an agent's detail view or from a file
   * in the library; `attach` is the upload that question is about, attached before it is sent.
   */
  validateSearch: (search: Record<string, unknown>): { ask?: string; attach?: string } => ({
    ask: typeof search.ask === "string" && search.ask.trim() ? search.ask : undefined,
    attach: uuid.safeParse(search.attach).success ? (search.attach as string) : undefined,
  }),
  component: () => null,
});
