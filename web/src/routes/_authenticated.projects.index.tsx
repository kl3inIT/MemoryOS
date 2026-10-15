import { createFileRoute } from "@tanstack/react-router";
import { ChatProjectsPage } from "@/features/chat/projects/chat-projects-page";
import { projectsSearchSchema } from "@/features/chat/projects/projects-filter";

export const Route = createFileRoute("/_authenticated/projects/")({
  validateSearch: projectsSearchSchema,
  component: ChatProjectsPage,
});
