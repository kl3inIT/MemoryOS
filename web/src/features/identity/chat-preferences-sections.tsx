import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { ArrowDownToLine, LayoutPanelTop, Sparkles } from "lucide-react";
import { SettingRow, SettingRows } from "@/components/composites/setting-row";
import { Select } from "@/components/ui/select";
import { Switch } from "@/components/ui/switch";
import { Textarea } from "@/components/ui/textarea";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { appText } from "@/i18n/app-text";
import { listAvailableChatModelsOptions } from "@/lib/hey-api/@tanstack/react-query.gen";
import { useChatPreferences, useSaveChatPreferences } from "./chat-preferences";

const PREFERENCES_LIMIT = 2000;

/** Onyx Chat Preferences: default model, default app mode, personal preferences and auto-scroll. */
export function ChatPreferencesSections() {
  const ui = useAppTranslation();
  const preferences = useChatPreferences();
  const save = useSaveChatPreferences();
  const models = useQuery({ ...listAvailableChatModelsOptions(), retry: false });
  // A draft only while typing; otherwise the saved value shows.
  const [typed, setTyped] = useState<string>();
  const data = preferences.data;
  const draft = typed ?? data?.personalPreferences ?? "";
  const disabled = !preferences.isSuccess || save.isPending;
  // Grouped by provider, as the Chat picker lists them.
  const byProvider = Map.groupBy(models.data ?? [], (model) => model.providerName);

  return (
    <>
      <section aria-labelledby="chat-defaults-heading" className="flex max-w-2xl flex-col gap-3">
        <h2 id="chat-defaults-heading" className="font-heading-h3 text-content-primary">
          {ui("New conversations")}
        </h2>
        <SettingRows>
          <SettingRow
            htmlFor="default-model"
            descriptionId="default-model-description"
            icon={<Sparkles />}
            title={ui("Default Model")}
            description={ui(
              "Preselected whenever you start a new chat. An assistant with its own model keeps it.",
            )}
            className="flex-col items-stretch sm:flex-row sm:items-center"
            control={
              <Select
                id="default-model"
                aria-describedby="default-model-description"
                className="sm:w-64"
                value={data?.defaultModelId ?? ""}
                disabled={disabled || !models.isSuccess}
                onChange={(event) =>
                  save.mutate({ defaultModelId: event.target.value || undefined })
                }
              >
                <option value="">{ui("Organization default")}</option>
                {[...byProvider].map(([provider, entries]) => (
                  <optgroup key={provider} label={provider}>
                    {entries.map((model) => (
                      <option key={model.id} value={model.id}>
                        {model.displayName}
                      </option>
                    ))}
                  </optgroup>
                ))}
              </Select>
            }
          />
          <SettingRow
            htmlFor="start-page"
            descriptionId="start-page-description"
            icon={<LayoutPanelTop />}
            title={ui("Default App Mode")}
            description={ui("Choose whether new sessions start in Search or Chat mode.")}
            className="flex-col items-stretch sm:flex-row sm:items-center"
            control={
              <Select
                id="start-page"
                aria-describedby="start-page-description"
                className="sm:w-64"
                value={data?.startPage ?? "CHAT"}
                disabled={disabled}
                onChange={(event) =>
                  save.mutate({ startPage: event.target.value === "SEARCH" ? "SEARCH" : "CHAT" })
                }
              >
                <option value="CHAT">{ui("Chat")}</option>
                <option value="SEARCH">{ui("Search")}</option>
              </Select>
            }
          />
        </SettingRows>
      </section>

      <section
        aria-labelledby="personal-preferences-heading"
        className="flex max-w-2xl flex-col gap-3"
      >
        <div>
          <h2 id="personal-preferences-heading" className="font-heading-h3 text-content-primary">
            <label htmlFor="personal-preferences">{ui("Personal Preferences")}</label>
          </h2>
          <p id="personal-preferences-description" className="text-content-muted">
            {ui("Describe how you want the system to behave and the tone it should use.")}
          </p>
        </div>
        <Textarea
          id="personal-preferences"
          aria-describedby="personal-preferences-description"
          rows={4}
          maxLength={PREFERENCES_LIMIT}
          value={draft}
          disabled={disabled}
          placeholder={ui(
            "For example: answer briefly in bullet points and always name the source document.",
          )}
          onChange={(event) => setTyped(event.target.value)}
          onBlur={() => {
            if (typed === undefined || !preferences.isSuccess) return;
            if (typed.trim() === data?.personalPreferences) setTyped(undefined);
            else
              save.mutate(
                { personalPreferences: typed.trim() },
                { onSettled: () => setTyped(undefined) },
              );
          }}
        />
        <p className="text-right font-secondary-body tabular-nums text-content-muted">
          {ui(appText("{{count}}/{{limit}}", { count: draft.length, limit: PREFERENCES_LIMIT }))}
        </p>
      </section>

      <section aria-labelledby="while-chatting-heading" className="flex max-w-2xl flex-col gap-3">
        <h2 id="while-chatting-heading" className="font-heading-h3 text-content-primary">
          {ui("While chatting")}
        </h2>
        <SettingRows>
          <SettingRow
            htmlFor="auto-scroll"
            descriptionId="auto-scroll-description"
            icon={<ArrowDownToLine />}
            title={ui("Chat Auto-scroll")}
            description={ui("Automatically scroll to new content as chat generates response.")}
            control={
              <Switch
                id="auto-scroll"
                aria-describedby="auto-scroll-description"
                checked={data?.autoScroll ?? true}
                disabled={disabled}
                onCheckedChange={(checked) => save.mutate({ autoScroll: checked })}
              />
            }
          />
        </SettingRows>
      </section>
      <p aria-live="polite" className="font-secondary-body text-content-muted">
        {save.isPending ? ui("Saving…") : save.isError ? ui("Couldn't save. Try again.") : ""}
      </p>
    </>
  );
}
