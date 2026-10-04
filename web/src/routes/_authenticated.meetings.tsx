import { createFileRoute } from "@tanstack/react-router";
import { MeetingsPage } from "@/features/meetings/meetings-page";
import { meetingsSearchSchema } from "@/features/meetings/meetings-search";

export const Route = createFileRoute("/_authenticated/meetings")({
  validateSearch: meetingsSearchSchema,
  component: MeetingsPage,
});
