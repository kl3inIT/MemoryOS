import { createFileRoute } from "@tanstack/react-router";
import { ChannelDetailPage } from "@/features/channels/channel-detail-page";

export const Route = createFileRoute("/_authenticated/admin/channels/$channelId")({
  component: ChannelDetailPage,
});
