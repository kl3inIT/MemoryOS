import { createFileRoute } from "@tanstack/react-router";
import { ChatPersonasPage } from "@/features/chat/chat-personas-page";
export const Route = createFileRoute("/_authenticated/assistants")({ component: ChatPersonasPage });
