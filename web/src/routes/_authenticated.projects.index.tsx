import { createFileRoute } from "@tanstack/react-router";
import { ChatProjectsPage } from "@/features/chat/chat-projects-page";
export const Route = createFileRoute("/_authenticated/projects/")({ component: ChatProjectsPage });
