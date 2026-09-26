import { useAppTranslation } from "@/i18n/use-app-translation";
import { useState, type ReactNode } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Lock, Share2, Users, type LucideIcon } from "lucide-react";
import { Alert, AlertAction, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import {
  Field,
  FieldContent,
  FieldDescription,
  FieldLabel,
  FieldTitle,
} from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import {
  getChatSharingOptions,
  getChatSharingQueryKey,
  setChatSharingMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { Sharing } from "@/lib/hey-api/types.gen";
import { FormDialog } from "@/components/composites/form-dialog";

/** Link sharing of a conversation as the API sends it; the published contract marks both fields optional. */
function sharingOf({ enabled = false, revision }: Sharing) {
  if (revision === undefined) throw new TypeError("Conversation sharing carries its revision.");
  return { enabled, revision };
}

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
  const path = { sessionId };
  const sharing = useQuery({
    ...getChatSharingOptions({ path }),
    select: sharingOf,
    enabled: open,
    staleTime: 0,
  });
  const save = useMutation({
    ...setChatSharingMutation(),
    onSuccess: (saved) => cache.setQueryData(getChatSharingQueryKey({ path }), saved),
  });
  const link = new URL(`/shared/${sessionId}`, window.location.origin).toString();
  const selected = enabled ?? sharing.data?.enabled ?? false;
  const ready = !!sharing.data && !sharing.isFetching && !sharing.isError;
  // The choice is local until save, so an authoritative refetch gates saving, not selecting.
  const choosable = !!sharing.data && !sharing.isError;
  async function copy() {
    try {
      await navigator.clipboard.writeText(link);
      setCopyState("copied");
    } catch {
      setCopyState("failed");
    }
  }
  const choices: { value: boolean; title: string; description: string; Icon: LucideIcon }[] = [
    {
      value: false,
      title: ui("Riêng tư"),
      description: ui("Chỉ mình bạn xem được hội thoại."),
      Icon: Lock,
    },
    {
      value: true,
      title: ui("Chia sẻ trong tổ chức"),
      description: ui("Người có liên kết cần đăng nhập."),
      Icon: Users,
    },
  ];
  return (
    <FormDialog
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
            <Share2 data-icon="inline-start" />
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
                await save.mutateAsync({
                  path,
                  body: { enabled: selected, revision: sharing.data!.revision },
                  signal: AbortSignal.timeout(30000),
                });
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
        <Alert variant="destructive">
          <AlertDescription>{ui("Không tải được quyền chia sẻ.")}</AlertDescription>
          <AlertAction>
            <Button
              type="button"
              size="sm"
              prominence="internal"
              onClick={() => void sharing.refetch()}
            >
              {ui("Tải lại")}
            </Button>
          </AlertAction>
        </Alert>
      )}
      {sharing.data && !sharing.isError && (
        <>
          <RadioGroup
            aria-label={ui("Quyền chia sẻ")}
            value={String(selected)}
            onValueChange={(next) => {
              setEnabled(next === "true");
              setCopyState("idle");
            }}
          >
            {choices.map(({ value, title, description, Icon }) => (
              <FieldLabel key={String(value)} htmlFor={`chat-sharing-${value}`}>
                <Field orientation="horizontal" data-disabled={!choosable || undefined}>
                  <RadioGroupItem
                    id={`chat-sharing-${value}`}
                    value={String(value)}
                    aria-label={title}
                    disabled={!choosable}
                  />
                  <Icon aria-hidden="true" className="size-5 shrink-0" />
                  <FieldContent>
                    <FieldTitle>{title}</FieldTitle>
                    <FieldDescription>{description}</FieldDescription>
                  </FieldContent>
                </Field>
              </FieldLabel>
            ))}
          </RadioGroup>
          {sharing.data.enabled && selected && (
            <Field>
              <FieldLabel htmlFor="chat-sharing-link">{ui("Liên kết chỉ đọc")}</FieldLabel>
              <Input
                id="chat-sharing-link"
                readOnly
                value={link}
                onFocus={(event) => event.target.select()}
              />
            </Field>
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
    </FormDialog>
  );
}
