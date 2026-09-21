import { createFileRoute } from "@tanstack/react-router";
import { ChatLibraryPage } from "@/features/chat/chat-library-page";

export const Route = createFileRoute("/_authenticated/library")({ component: ChatLibraryPage });
