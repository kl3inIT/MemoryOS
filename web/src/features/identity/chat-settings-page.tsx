import { PersonalPromptShortcuts } from "@/features/agents/prompt-shortcuts";
import { VoiceSettingsSection } from "@/features/voice/voice-settings-section";
import { PageHeader, SettingsLayout } from "@/components/ui/settings-layout";
import { useAppTranslation } from "@/i18n/use-app-translation";

/** Onyx "Chat Preferences": how Chat behaves for this member (MEM-145). */
export function ChatSettingsPage() {
  const ui = useAppTranslation();
  return (
    <SettingsLayout>
      <PageHeader
        eyebrow={ui("Settings")}
        title={ui("Chat")}
        description={ui("Preferences for your conversations.")}
      />
      <VoiceSettingsSection />
      <PersonalPromptShortcuts />
    </SettingsLayout>
  );
}
