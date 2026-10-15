import { createFileRoute } from "@tanstack/react-router";
import { ChannelLinksSection } from "@/features/channels/channel-links-section";
import { ChatSettingsPage } from "@/features/chat/settings/chat-settings-page";

export const Route = createFileRoute("/_authenticated/settings/chat")({
  component: ChatSettings,
});

function ChatSettings() {
  return <ChatSettingsPage channelLinks={<ChannelLinksSection />} />;
}
