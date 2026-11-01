import { useQuery } from "@tanstack/react-query";
import { BotMessageSquare } from "lucide-react";
import { SettingRow, SettingRows } from "@/components/composites/setting-row";
import { NativeSelect } from "@/components/ui/native-select";
import { personasOptions } from "@/features/chat/chat-personas-api";
import { useChatPreferences, useSaveChatPreferences } from "@/features/identity/chat-preferences";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { listChannelLinksOptions } from "@/lib/hey-api/@tanstack/react-query.gen";

/**
 * The assistant a member's direct messages to the Tenant's bots answer with (MEM-299), in place of each bot's own:
 * with their own app connections, it is their personal bot. Saved on change; shown only when the Tenant has a bot.
 * A group chat and a public bot keep the administrator's choice.
 */
export function ChannelDirectAssistantSection() {
  const ui = useAppTranslation();
  const bots = useQuery(listChannelLinksOptions());
  const preferences = useChatPreferences();
  const save = useSaveChatPreferences();
  const assistants = useQuery({ ...personasOptions(), enabled: (bots.data?.length ?? 0) > 0 });
  if (!bots.data?.length) return null;
  const chosen = preferences.data?.channelPersonaId ?? "";

  return (
    <section aria-labelledby="direct-assistant-heading" className="flex max-w-2xl flex-col gap-3">
      <h2 id="direct-assistant-heading" className="font-heading-h3 text-content-primary">
        {ui("Nhắn riêng bot")}
      </h2>
      <SettingRows>
        <SettingRow
          htmlFor="direct-assistant"
          icon={<BotMessageSquare />}
          title={ui("Trợ lý trả lời")}
          className="flex-col items-stretch sm:flex-row sm:items-center"
          control={
            <NativeSelect
              id="direct-assistant"
              className="sm:w-64"
              value={chosen}
              disabled={!preferences.isSuccess || save.isPending}
              onChange={(event) =>
                save.mutate({ channelPersonaId: event.target.value || undefined })
              }
            >
              <option value="">{ui("Theo bot")}</option>
              {(assistants.data ?? []).map((assistant) => (
                <option key={assistant.id} value={assistant.id}>
                  {assistant.name}
                </option>
              ))}
            </NativeSelect>
          }
        />
      </SettingRows>
    </section>
  );
}
