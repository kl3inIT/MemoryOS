import {
  ModelSelectorContent,
  ModelSelectorEffort,
  ModelSelectorEmpty,
  ModelSelectorGroup,
  ModelSelectorItem,
  ModelSelectorList,
  ModelSelectorRoot,
  ModelSelectorSearch,
  ModelSelectorTrigger,
  ModelSelectorValue,
  type ModelOption,
  type ModelSelectorEffortOption,
} from "@/components/assistant-ui/elements/model-selector";
import { appText } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { DataBoundaryTag } from "./data-boundary";
import type { ManagedModel, ManagedProvider } from "./model-catalog";
import { ModelLogo } from "./model-logo";
import { ReasoningLevel } from "./reasoning-level";

export type ModelPickerOption = {
  model: ManagedModel;
  provider: ManagedProvider;
  disabled?: boolean;
  note?: string;
};

/**
 * A Models page selection on assistant-ui's Model selector, as Chat's: search, one group per provider with its data
 * boundary, model logos. With {@code efforts}, a model that reasons ends the popover in the selector's Thinking row,
 * kept open while a level is picked, and the trigger shows the level beside the model.
 */
export function ModelPicker({
  value,
  options,
  disabled,
  placeholder,
  ariaLabel,
  onChange,
  efforts,
  effort,
  onEffortChange,
}: {
  value: string;
  options: ModelPickerOption[];
  disabled?: boolean;
  placeholder: string;
  ariaLabel: string;
  onChange: (modelId: string) => void;
  /** The levels a model that reasons runs at; absent where the level is not chosen here. */
  efforts?: readonly ModelSelectorEffortOption[];
  effort?: string;
  onEffortChange?: (effort: string) => void;
}) {
  const ui = useAppTranslation();
  const choices = options.map((option): ModelOption => ({
    id: option.model.id,
    name: option.model.displayName,
    description: option.note
      ? ui(appText("{{model}} · {{note}}", { model: option.model.modelName, note: option.note }))
      : option.model.modelName !== option.model.displayName
        ? option.model.modelName
        : undefined,
    icon: <ModelLogo modelName={option.model.modelName} />,
    disabled: option.disabled,
    keywords: [option.model.modelName, option.provider.name],
    efforts: option.model.settings.capabilities.reasoning ? efforts : undefined,
  }));
  const groups = new Map<string, { provider: ManagedProvider; choices: ModelOption[] }>();
  options.forEach((option, index) => {
    const group = groups.get(option.provider.id) ?? { provider: option.provider, choices: [] };
    group.choices.push(choices[index]!);
    groups.set(option.provider.id, group);
  });
  const selected = choices.find((choice) => choice.id === value);
  const level = selected?.efforts ? efforts?.find((option) => option.id === effort) : undefined;

  return (
    <ModelSelectorRoot
      models={choices}
      value={value}
      onValueChange={onChange}
      effort={effort}
      onEffortChange={onEffortChange}
    >
      <ModelSelectorTrigger
        aria-label={ariaLabel}
        disabled={disabled}
        className="max-w-full min-w-0"
      >
        <ModelSelectorValue placeholder={placeholder} showEffort={false} />
        <ReasoningLevel level={level?.id} name={level?.name} />
      </ModelSelectorTrigger>
      <ModelSelectorContent className="w-[min(24rem,calc(100vw-2rem))]" align="end">
        <ModelSelectorSearch aria-label={ui("Search models")} placeholder={ui("Search models…")} />
        <ModelSelectorList>
          <ModelSelectorEmpty>{ui("No matching models.")}</ModelSelectorEmpty>
          {[...groups.values()].map((group) => (
            <ModelSelectorGroup
              key={group.provider.id}
              heading={
                <span className="flex items-center gap-2">
                  <span className="min-w-0 flex-1 truncate">{group.provider.name}</span>
                  <DataBoundaryTag boundary={group.provider.dataBoundary} />
                </span>
              }
            >
              {group.choices.map((choice) => (
                <ModelSelectorItem key={choice.id} model={choice} />
              ))}
            </ModelSelectorGroup>
          ))}
        </ModelSelectorList>
        <ModelSelectorEffort label={ui("Reasoning")} />
      </ModelSelectorContent>
    </ModelSelectorRoot>
  );
}
