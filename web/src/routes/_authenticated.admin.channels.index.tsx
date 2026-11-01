import { createFileRoute } from "@tanstack/react-router";
import { ChannelsPage } from "@/features/channels/channels-page";

export const Route = createFileRoute("/_authenticated/admin/channels/")({
  component: ChannelsPage,
});
