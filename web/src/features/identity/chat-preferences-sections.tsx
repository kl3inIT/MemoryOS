import { useState } from "react";
import { ArrowDownToLine, Brain, Sparkles, Thermometer } from "lucide-react";
import {
  ModelSelectorContent,
  ModelSelectorEmpty,
  ModelSelectorGroup,
  ModelSelectorItem,
  ModelSelectorList,
  ModelSelectorRoot,
  ModelSelectorSearch,
  ModelSelectorTrigger,
} from "@/components/assistant-ui/elements/model-selector";
import { SettingRow, SettingRows } from "@/components/composites/setting-row";
import { Select } from "@/components/ui/select";
import { Slider } from "@/components/ui/slider";
import { Switch } from "@/components/ui/switch";
import { Textarea } from "@/components/ui/textarea";
import { REASONING_EFFORTS, useChatModels } from "@/features/chat/chat-models";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { appText } from "@/i18n/app-text";
import type { ChatPreferencesInput } from "@/lib/hey-api/types.gen";
import { useChatPreferences, useSaveChatPreferences } from "./chat-preferences";

const PREFERENCES_LIMIT = 2000;
/** Stands for "no personal default"; the Tenant default then applies. */
const INHERITED = "inherited";

/** Onyx Chat Preferences: default model, personal preferences and auto-scroll. */
export function ChatPreferencesSections() {
  const ui = useAppTranslation();
  const preferences = useChatPreferences();
  const save = useSaveChatPreferences();
  // The picker the composer uses, with the same provider groups and vendor marks.
  const { catalog, groups } = useChatModels();
  // A draft only while typing; otherwise the saved value shows.
  const [typed, setTyped] = useState<string>();
  // The slider shows its own value while dragging; the saved value takes over once it is committed.
  const [temperature, setTemperature] = useState<number>();
  const data = preferences.data;
  const draft = typed ?? data?.personalPreferences ?? "";
  const disabled = !preferences.isSuccess || save.isPending;
  const inherited = {
    id: INHERITED,
    name: ui("Organization default"),
    description: ui("The model your administrator chose."),
  };
  const options = [inherited, ...groups.flatMap((group) => group.models)];

  return (
    <>
      <section aria-labelledby="chat-defaults-heading" className="flex max-w-2xl flex-col gap-3">
        <h2 id="chat-defaults-heading" className="font-heading-h3 text-content-primary">
          {ui("Chats")}
        </h2>
        <SettingRows>
          <SettingRow
            descriptionId="default-model-description"
            icon={<Sparkles />}
            title={ui("Default Model")}
            description={ui("This model will be used by default in your chats.")}
            className="flex-col items-stretch sm:flex-row sm:items-center"
            control={
              <ModelSelectorRoot
                models={options}
                value={data?.defaultModelId ?? INHERITED}
                onValueChange={(value) =>
                  save.mutate({ defaultModelId: value === INHERITED ? undefined : value })
                }
              >
                <ModelSelectorTrigger
                  aria-label={ui("Default Model")}
                  aria-describedby="default-model-description"
                  disabled={disabled || !catalog.isSuccess}
                  className="h-9 rounded-lg border-border-subtle bg-surface-raised sm:w-64"
                />
                <ModelSelectorContent className="w-80 max-w-[calc(100vw-2rem)]" align="end">
                  <ModelSelectorSearch
                    aria-label={ui("Search models")}
                    placeholder={ui("Search models…")}
                  />
                  <ModelSelectorList>
                    <ModelSelectorEmpty>{ui("No models found")}</ModelSelectorEmpty>
                    <ModelSelectorGroup>
                      <ModelSelectorItem model={inherited} />
                    </ModelSelectorGroup>
                    {groups.map((group) => (
                      <ModelSelectorGroup key={group.provider} heading={group.provider}>
                        {group.models.map((model) => (
                          <ModelSelectorItem key={model.id} model={model} />
                        ))}
                      </ModelSelectorGroup>
                    ))}
                  </ModelSelectorList>
                </ModelSelectorContent>
              </ModelSelectorRoot>
            }
          />
          <SettingRow
            htmlFor="default-temperature"
            descriptionId="default-temperature-description"
            icon={<Thermometer />}
            title={ui("Default Creativity")}
            description={ui(
              "Starting creativity for your new chats. A model the administrator pinned keeps its own value.",
            )}
            className="flex-col items-stretch sm:flex-row sm:items-center"
            control={
              <div className="flex items-center gap-3 sm:w-64">
                <Slider
                  id="default-temperature"
                  aria-describedby="default-temperature-description"
                  className="flex-1"
                  min={0}
                  max={2}
                  step={0.1}
                  disabled={disabled}
                  value={[data?.temperatureDefault ?? 1]}
                  onValueChange={([next]) => setTemperature(next)}
                  onValueCommit={([next]) => save.mutate({ temperatureDefault: next })}
                />
                <span className="w-8 text-right font-secondary-body tabular-nums text-content-secondary">
                  {(temperature ?? data?.temperatureDefault ?? 1).toFixed(1)}
                </span>
              </div>
            }
          />
          <SettingRow
            htmlFor="default-reasoning"
            descriptionId="default-reasoning-description"
            icon={<Brain />}
            title={ui("Default Reasoning Level")}
            description={ui(
              "Starting reasoning level for your new chats. Any single chat can pin its own level.",
            )}
            className="flex-col items-stretch sm:flex-row sm:items-center"
            control={
              <Select
                id="default-reasoning"
                aria-describedby="default-reasoning-description"
                className="sm:w-64"
                value={data?.reasoningEffortDefault ?? ""}
                disabled={disabled}
                onChange={(event) =>
                  save.mutate({
                    reasoningEffortDefault: (event.target.value ||
                      undefined) as ChatPreferencesInput["reasoningEffortDefault"],
                  })
                }
              >
                <option value="">{ui("Model default")}</option>
                {REASONING_EFFORTS.map((level) => (
                  <option key={level.id} value={level.id}>
                    {ui(level.name)}
                  </option>
                ))}
              </Select>
            }
          />
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

      <section
        aria-labelledby="personal-preferences-heading"
        className="flex max-w-2xl flex-col gap-3"
      >
        <div>
          <h2 id="personal-preferences-heading" className="font-heading-h3 text-content-primary">
            <label htmlFor="personal-preferences">{ui("Personal Preferences")}</label>
          </h2>
          <p id="personal-preferences-description" className="text-content-muted">
            {ui("Provide your custom preferences in natural language.")}
          </p>
        </div>
        <Textarea
          id="personal-preferences"
          aria-describedby="personal-preferences-description"
          rows={4}
          maxLength={PREFERENCES_LIMIT}
          value={draft}
          disabled={disabled}
          placeholder={ui("Describe how you want the system to behave and the tone it should use.")}
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
      <p aria-live="polite" className="font-secondary-body text-content-muted">
        {save.isPending ? ui("Saving…") : save.isError ? ui("Couldn't save. Try again.") : ""}
      </p>
    </>
  );
}
