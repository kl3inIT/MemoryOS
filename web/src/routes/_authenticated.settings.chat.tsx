import { createFileRoute } from "@tanstack/react-router";
import { ChannelDirectAssistantSection } from "@/features/channels/channel-direct-assistant";
import { ChannelLinksSection } from "@/features/channels/channel-links-section";
import { ChatSettingsPage } from "@/features/chat/settings/chat-settings-page";

export const Route = createFileRoute("/_authenticated/settings/chat")({
  component: function ChatSettingsRoute() {
    return (
      <ChatSettingsPage
        channelLinks={
          <>
            <ChannelLinksSection />
            <ChannelDirectAssistantSection />
          </>
        }
      />
    );
  },
});
