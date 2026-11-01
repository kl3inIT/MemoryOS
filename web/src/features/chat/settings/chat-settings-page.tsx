import type { ReactNode } from "react";
import { MessageSquare } from "lucide-react";
import { PersonalPromptShortcuts } from "@/features/agents/prompt-shortcuts";
import { VoiceSettingsSection } from "@/features/voice/voice-settings-section";
import { PageHeader, SettingsLayout } from "@/components/composites/settings-layout";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { ChatArchiveSection } from "./chat-archive-section";
import { ChatExportSection } from "./chat-export-section";
import { ChatMemorySection } from "./chat-memory-section";
import { ChatRetentionSection } from "./chat-retention-section";
import { ChatPreferencesSections } from "./chat-preferences-sections";

/**
 * Onyx "Chat Preferences": how Chat behaves for this member (MEM-145). `channelLinks` is the chat bots' part, which
 * the route composes because `chat` does not import `channels`.
 */
export function ChatSettingsPage({ channelLinks }: { channelLinks?: ReactNode }) {
  const ui = useAppTranslation();
  return (
    <SettingsLayout>
      <PageHeader
        icon={<MessageSquare />}
        title={ui("Chat")}
        description={ui("Preferences for your conversations.")}
      />
      <ChatPreferencesSections />
      <ChatMemorySection />
      {channelLinks}
      <ChatArchiveSection />
      <ChatRetentionSection />
      <ChatExportSection />
      <VoiceSettingsSection />
      <div className="max-w-2xl">
        <PersonalPromptShortcuts />
      </div>
    </SettingsLayout>
  );
}
