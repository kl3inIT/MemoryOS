import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { ArrowLeft, Check, Globe, Settings, SlidersHorizontal } from "lucide-react";
import { Link } from "@tanstack/react-router";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import {
  Command,
  CommandInput,
  CommandEmpty,
  CommandList,
  CommandItem,
  CommandGroup,
} from "@/components/ui/command";
import { Button } from "@/components/ui/button";
import { IconButton } from "@/components/ui/icon-button";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { getChatWebAvailability } from "@/lib/hey-api/sdk.gen";
import { useAppTranslation } from "@/i18n/use-app-translation";

export type WebSearchMode = "off" | "auto" | "required";
export function ChatActionsMenu({
  value,
  onChange,
  disabled,
  sessionId,
  modelId,
}: {
  value: WebSearchMode;
  onChange: (mode: WebSearchMode) => void;
  disabled: boolean;
  sessionId?: string;
  modelId?: string;
}) {
  const ui = useAppTranslation();
  const session = useApplicationSession();
  const [open, setOpen] = useState(false);
  const [configure, setConfigure] = useState(false);
  const available = useQuery({
    queryKey: ["chat-web", session.actorId, session.authorizationVersion, sessionId],
    queryFn: async ({ signal }) =>
      (await getChatWebAvailability({ query: { sessionId }, signal, throwOnError: true })).data,
    retry: false,
  });
  const selectedModel = modelId ?? available.data?.inheritedModelId;
  const supported = (mode: WebSearchMode) =>
    mode === "off" ||
    !!(
      available.data?.searchAvailable &&
      selectedModel &&
      (mode === "required"
        ? available.data.requiredModelIds
        : available.data.automaticModelIds
      )?.includes(selectedModel)
    );
  const labels = {
    off: ui("Tắt Web"),
    auto: ui("Tự động dùng Web"),
    required: ui("Bắt buộc tìm trên Web"),
  };
  return (
    <Popover
      open={open}
      onOpenChange={(next) => {
        setOpen(next);
        if (!next) setConfigure(false);
      }}
    >
      <PopoverTrigger asChild>
        <Button
          size="sm"
          prominence="internal"
          disabled={disabled}
          aria-label={ui("Hành động")}
          title={labels[value]}
        >
          <SlidersHorizontal aria-hidden="true" className="size-4" />
          {value !== "off" && <span>{ui("Web")}</span>}
        </Button>
      </PopoverTrigger>
      <PopoverContent
        side="top"
        align="start"
        collisionPadding={16}
        className="max-w-[calc(100vw-2rem)]"
      >
        {configure && (
          <Button
            size="sm"
            prominence="internal"
            aria-label={ui("Quay lại danh sách hành động")}
            onClick={() => setConfigure(false)}
          >
            <ArrowLeft className="size-4" />
            {ui("Hành động")}
          </Button>
        )}
        <Command key={String(configure)} label={ui("Hành động")}>
          {!configure && <CommandInput placeholder={ui("Tìm hành động…")} />}
          <CommandList>
            <CommandEmpty>{ui("Không có hành động phù hợp.")}</CommandEmpty>
            {!configure ? (
              <CommandGroup>
                <div className="hidden items-center has-[[cmdk-item]]:flex">
                  <CommandItem
                    className="min-w-0 flex-1"
                    value={ui("Tìm kiếm Web")}
                    disabled={value === "off" && !supported("auto") && !supported("required")}
                    onSelect={() => {
                      onChange(
                        value === "off" ? (supported("required") ? "required" : "auto") : "off",
                      );
                      setOpen(false);
                    }}
                  >
                    <Globe className="size-4" aria-hidden="true" />
                    {ui("Tìm kiếm Web")}
                    {value !== "off" && <Check className="ml-auto size-4" aria-hidden="true" />}
                  </CommandItem>
                  <IconButton
                    size="sm"
                    prominence="internal"
                    aria-label={ui("Tùy chọn Web")}
                    onClick={() => setConfigure(true)}
                  >
                    <Settings className="size-4" aria-hidden="true" />
                  </IconButton>
                </div>
              </CommandGroup>
            ) : (
              <CommandGroup heading={ui("Tìm kiếm và đọc trang Web")}>
                {(["off", "auto", "required"] as const).map((mode) => (
                  <CommandItem
                    key={mode}
                    disabled={!supported(mode)}
                    onSelect={() => {
                      onChange(mode);
                      setOpen(false);
                    }}
                  >
                    {labels[mode]}
                    {mode === value && <Check aria-hidden="true" className="ml-auto size-4" />}
                  </CommandItem>
                ))}
              </CommandGroup>
            )}
          </CommandList>
        </Command>
        {available.isError ? (
          <Button size="sm" prominence="internal" onClick={() => void available.refetch()}>
            {ui("Tải lại")}
          </Button>
        ) : !available.isPending && !available.data?.searchAvailable ? (
          <p className="px-2 text-xs text-content-muted">{ui("Chưa kết nối công cụ tìm kiếm.")}</p>
        ) : null}
        {configure && session.capabilities.includes("MODELS_MANAGE") && (
          <Link
            to="/settings/web"
            className="flex items-center gap-2 rounded px-2 py-2 text-sm hover:bg-surface-sunken"
            onClick={() => setOpen(false)}
          >
            <Settings aria-hidden="true" className="size-4" />
            {ui("Cài đặt Web")}
          </Link>
        )}
      </PopoverContent>
    </Popover>
  );
}
