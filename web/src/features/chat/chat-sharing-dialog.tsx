import { useAppTranslation } from "@/i18n/use-app-translation";
import { useState, type ReactNode } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Lock, Share2, Users } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { sameOriginMutationHeaders } from "@/lib/api";
import { getChatSharing, setChatSharing } from "@/lib/hey-api/sdk.gen";
import { ChatDialog } from "./chat-dialog";
import { sharingSchema } from "./chat-workspace-api";
import { cn } from "@/lib/utils";

export function SharingDialog({
  sessionId,
  open: controlledOpen,
  onOpenChange,
  trigger,
}: {
  sessionId: string;
  open?: boolean;
  onOpenChange?: (open: boolean) => void;
  trigger?: ReactNode;
}) {
  const ui = useAppTranslation();

  const [internalOpen, setOpen] = useState(false);
  const open = controlledOpen ?? internalOpen;
  const [enabled, setEnabled] = useState<boolean>();
  const [copyState, setCopyState] = useState<"idle" | "copied" | "failed">("idle");
  const cache = useQueryClient();
  const sharing = useQuery({
    queryKey: ["chat-sharing", sessionId],
    queryFn: async ({ signal }) =>
      sharingSchema.parse(
        (await getChatSharing({ path: { sessionId }, signal, throwOnError: true })).data,
      ),
    enabled: open,
    staleTime: 0,
  });
  const link = new URL(`/shared/${sessionId}`, window.location.origin).toString();
  const selected = enabled ?? sharing.data?.enabled ?? false;
  const ready = !!sharing.data && !sharing.isFetching && !sharing.isError;
  async function copy() {
    try {
      await navigator.clipboard.writeText(link);
      setCopyState("copied");
    } catch {
      setCopyState("failed");
    }
  }
  return (
    <ChatDialog
      title={ui("Chia sẻ hội thoại")}
      description={ui(
        "Thành viên trong tổ chức có liên kết và đã đăng nhập được xem hội thoại này.",
      )}
      open={open}
      onOpenChange={(next) => {
        setOpen(next);
        onOpenChange?.(next);
        setEnabled(undefined);
        setCopyState("idle");
      }}
      trigger={
        trigger ??
        (controlledOpen === undefined ? (
          <Button size="sm" prominence="internal">
            <Share2 className="size-4" />
            <span className="hidden sm:inline">{ui("Chia sẻ")}</span>
            <span className="sr-only sm:hidden">{ui("Chia sẻ")}</span>
          </Button>
        ) : undefined)
      }
      closeOnSuccess={false}
      submitLabel={
        selected
          ? sharing.data?.enabled
            ? ui("Sao chép liên kết")
            : ui("Tạo liên kết")
          : ui("Thu hồi liên kết")
      }
      submitDisabled={!ready || (!selected && !sharing.data?.enabled)}
      onSubmit={
        ready
          ? async () => {
              if (selected !== sharing.data!.enabled) {
                const { data } = await setChatSharing({
                  path: { sessionId },
                  body: { enabled: selected, revision: sharing.data!.revision },
                  headers: sameOriginMutationHeaders,
                  signal: AbortSignal.timeout(30000),
                  throwOnError: true,
                });
                cache.setQueryData(["chat-sharing", sessionId], sharingSchema.parse(data));
                setEnabled(undefined);
              }
              if (selected) await copy();
              else setCopyState("idle");
            }
          : undefined
      }
    >
      {sharing.isPending && <p role="status">{ui("Đang tải quyền chia sẻ…")}</p>}
      {sharing.isError && (
        <p role="alert">
          {ui("Không tải được quyền chia sẻ.")}{" "}
          <Button type="button" prominence="internal" onClick={() => void sharing.refetch()}>
            {ui("Tải lại")}
          </Button>
        </p>
      )}
      {sharing.data && !sharing.isError && (
        <>
          <div role="radiogroup" aria-label={ui("Quyền chia sẻ")} className="space-y-2">
            {[
              {
                value: false,
                title: "Riêng tư",
                description: "Chỉ mình bạn xem được hội thoại.",
                Icon: Lock,
              },
              {
                value: true,
                title: "Chia sẻ trong tổ chức",
                description: "Người có liên kết cần đăng nhập.",
                Icon: Users,
              },
            ].map(({ value, title, description, Icon }) => (
              <label
                key={title}
                className={cn(
                  "flex w-full cursor-pointer items-center gap-3 rounded-xl border p-3 text-left has-focus-visible:ring-2 has-focus-visible:ring-ring has-disabled:opacity-50",
                  selected === value
                    ? "border-border-strong bg-surface-sunken"
                    : "border-border-subtle hover:bg-surface-sunken",
                )}
              >
                <input
                  type="radio"
                  name="sharing-access"
                  className="size-4 shrink-0 accent-content-primary"
                  aria-label={ui(title)}
                  checked={selected === value}
                  disabled={!ready}
                  onChange={() => {
                    setEnabled(value);
                    setCopyState("idle");
                  }}
                />
                <Icon className="size-5 shrink-0" />
                <span>
                  <span className="block font-medium">{ui(title)}</span>
                  <span className="text-sm text-content-secondary">{ui(description)}</span>
                </span>
              </label>
            ))}
          </div>
          {sharing.data.enabled && selected && (
            <label className="block space-y-2">
              <span className="text-sm">{ui("Liên kết chỉ đọc")}</span>
              <Input
                aria-label={ui("Liên kết chỉ đọc")}
                readOnly
                value={link}
                onFocus={(event) => event.target.select()}
              />
            </label>
          )}
          <p role="status" className="text-sm text-content-secondary">
            {copyState === "copied"
              ? ui("Đã sao chép liên kết.")
              : copyState === "failed"
                ? ui("Chưa sao chép được. Bạn có thể chọn liên kết ở ô trên để sao chép thủ công.")
                : sharing.data.enabled
                  ? ui("Đang chia sẻ.")
                  : ui("Hội thoại đang riêng tư.")}
          </p>
        </>
      )}
    </ChatDialog>
  );
}
