import { useAppTranslation } from "@/i18n/use-app-translation";
import { useQuery } from "@tanstack/react-query";
import { Bot } from "lucide-react";
import { OpenAILogo, ClaudeLogo, GeminiLogo } from "@/components/assistant-ui/elements/logos";
import {
  ModelSelectorRoot,
  ModelSelectorTrigger,
  ModelSelectorContent,
  ModelSelectorGroup,
  ModelSelectorItem,
  ModelSelectorSearch,
  ModelSelectorList,
  ModelSelectorEmpty,
} from "@/components/assistant-ui/elements/model-selector";
import { Button } from "@/components/ui/button";
import { listAvailableChatModels } from "@/lib/hey-api/sdk.gen";
import { useApplicationSession } from "@/features/identity/application-session-context";

export function ChatModelPicker({
  sessionId,
  value,
  onChange,
  disabled,
}: {
  sessionId?: string;
  value?: string;
  onChange: (id?: string) => void;
  disabled: boolean;
}) {
  const ui = useAppTranslation();

  const { actorId, authorizationVersion } = useApplicationSession();
  const catalog = useQuery({
    queryKey: ["chat-models", actorId, authorizationVersion, sessionId],
    queryFn: async ({ signal }) =>
      (await listAvailableChatModels({ query: { sessionId }, signal, throwOnError: true })).data,
    retry: false,
  });
  if (catalog.isError)
    return (
      <Button size="sm" prominence="internal" onClick={() => void catalog.refetch()}>
        {ui("Tải lại mô hình")}
      </Button>
    );
  const models = (catalog.data ?? []).flatMap((model) =>
    model.id
      ? [
          {
            id: model.id,
            name: model.displayName || model.modelName || ui("Model"),
            provider: model.providerName ?? ui("Models"),
            icon: <ModelLogo modelName={model.modelName ?? ""} />,
          },
        ]
      : [],
  );
  const options = [
    {
      id: "auto",
      name: ui("Tự động"),
      icon: <Bot aria-hidden="true" className="size-4" />,
    },
    ...models,
  ];
  const groups = [...new Set(models.map((model) => model.provider))];
  return (
    <ModelSelectorRoot
      models={options}
      value={value ?? "auto"}
      onValueChange={(id) => onChange(id === "auto" ? undefined : id)}
    >
      <ModelSelectorTrigger
        aria-label={ui("Chọn mô hình")}
        variant="ghost"
        size="sm"
        disabled={disabled || catalog.isPending || models.length === 0}
        className="min-w-0 max-w-[min(18rem,50vw)] text-content-secondary"
      >
        {catalog.isPending
          ? ui("Đang tải mô hình…")
          : models.length === 0
            ? ui("Chưa có mô hình khả dụng")
            : value && !models.some((model) => model.id === value)
              ? ui("Mô hình đã chọn không khả dụng")
              : undefined}
      </ModelSelectorTrigger>
      <ModelSelectorContent className="w-80 max-w-[calc(100vw-2rem)]" align="start">
        <ModelSelectorSearch aria-label={ui("Tìm mô hình")} placeholder={ui("Tìm mô hình…")} />
        <ModelSelectorList>
          <ModelSelectorEmpty>{ui("Không tìm thấy mô hình.")}</ModelSelectorEmpty>
          <ModelSelectorGroup>
            <ModelSelectorItem model={options[0]!} />
          </ModelSelectorGroup>
          {groups.map((provider) => (
            <ModelSelectorGroup heading={provider} key={provider}>
              {models
                .filter((model) => model.provider === provider)
                .map((model) => (
                  <ModelSelectorItem key={model.id} model={model} />
                ))}
            </ModelSelectorGroup>
          ))}
        </ModelSelectorList>
      </ModelSelectorContent>
    </ModelSelectorRoot>
  );
}

function ModelLogo({ modelName }: { modelName: string }) {
  // Branding is display-only; configuration UUIDs still select and authorize the model.
  const name = modelName.split("/").at(-1)?.toLowerCase() ?? "";
  if (/^(gpt-|o\d)/.test(name)) return <OpenAILogo className="size-4" />;
  if (name.startsWith("claude-")) return <ClaudeLogo className="size-4" />;
  if (name.startsWith("gemini-")) return <GeminiLogo className="size-4" />;
  return <Bot aria-hidden="true" className="size-4" />;
}
