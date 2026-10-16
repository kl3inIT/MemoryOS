import { createFileRoute } from "@tanstack/react-router";
import { z } from "zod";
import { TopicHistoryPage } from "@/features/chat/session/topic-history-page";

export const Route = createFileRoute("/_authenticated/history")({
  /** `assistant` keeps the topics of one assistant, `q` searches titles and messages. */
  validateSearch: z.object({
    assistant: z.string().uuid().optional().catch(undefined),
    q: z.string().max(200).optional().catch(undefined),
  }),
  component: TopicHistoryPage,
});
