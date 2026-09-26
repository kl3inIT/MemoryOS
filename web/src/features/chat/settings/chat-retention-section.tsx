import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { CalendarClock } from "lucide-react";
import { SettingRow, SettingRows } from "@/components/composites/setting-row";
import { Alert, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Field, FieldError, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { useAppTranslation } from "@/i18n/use-app-translation";
import {
  getChatRetentionOptions,
  getChatRetentionQueryKey,
  previewChatRetentionOptions,
  previewChatRetentionQueryKey,
  saveChatRetentionMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import { actionErrorText } from "@/lib/action-errors";
import { useRefreshChatSessions } from "@/features/chat/runtime/chat-threads-context";

const MAX_DAYS = 3650;
/** The windows worth one click; anything else is typed in, as ChatGPT's own short list works. */
const PRESETS = [30, 90, 180, 365] as const;
const OFF = "off";
const CUSTOM = "custom";

/**
 * Self-deleting conversations in one's own settings: a conversation nobody has touched for this many days is
 * deleted as if its owner had deleted it. The number belongs to the person, not to an administrator, because
 * it deletes nothing but their own history — and because deleting is not undoable, how many conversations the
 * number would take is read before it can be saved.
 */
export function ChatRetentionSection() {
  const ui = useAppTranslation();
  const policy = useQuery(getChatRetentionOptions());

  return (
    <section aria-labelledby="chat-retention-heading" className="flex max-w-2xl flex-col gap-3">
      <h2 id="chat-retention-heading" className="font-heading-h3 text-content-primary">
        {ui("Tự xoá hội thoại")}
      </h2>
      {policy.isPending && <Skeleton className="h-24 w-full" />}
      {policy.isError && (
        <Alert variant="destructive">
          <AlertTitle>{ui("Không tải được thiết lập tự xoá.")}</AlertTitle>
          <Button size="sm" prominence="internal" onClick={() => void policy.refetch()}>
            {ui("Thử lại")}
          </Button>
        </Alert>
      )}
      {/* Keyed on what was saved, so the form starts from it without an effect writing state. */}
      {policy.isSuccess && <RetentionForm key={policy.data.days ?? OFF} saved={policy.data.days} />}
    </section>
  );
}

function RetentionForm({ saved }: { saved: number | null }) {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const refreshSessions = useRefreshChatSessions();
  const preset =
    saved === null ? OFF : (PRESETS as readonly number[]).includes(saved) ? String(saved) : CUSTOM;
  const [choice, setChoice] = useState<string>(preset);
  const [custom, setCustom] = useState(String(saved ?? 90));

  const typed = Number.parseInt(custom, 10);
  const valid = Number.isInteger(typed) && typed >= 1 && typed <= MAX_DAYS;
  const days = choice === OFF ? null : choice === CUSTOM ? (valid ? typed : null) : Number(choice);
  const unchanged = days === saved;

  const preview = useQuery({
    ...previewChatRetentionOptions({ query: { days: days ?? undefined } }),
    enabled: days !== null,
  });
  const affected = days === null ? 0 : (preview.data?.affected ?? 0);

  const save = useMutation({
    ...saveChatRetentionMutation(),
    onSuccess: () =>
      Promise.all([
        cache.invalidateQueries({ queryKey: getChatRetentionQueryKey() }),
        cache.invalidateQueries({ queryKey: previewChatRetentionQueryKey() }),
        refreshSessions(),
      ]),
  });

  return (
    <SettingRows>
      <SettingRow
        // The same row serves a wide settings page and a narrow sheet. The sentence keeps a floor on its
        // width, so in the sheet the control wraps onto its own line rather than squeezing the words into a
        // column one syllable wide.
        className="flex-wrap gap-y-3 [&>div:first-of-type]:min-w-56"
        icon={<CalendarClock />}
        title={ui("Xoá hội thoại sau")}
        description={ui(
          "Tính từ lần cuối bạn nhắn trong hội thoại đó. Tệp trong thư viện giữ vòng đời riêng.",
        )}
        control={
          <Select
            value={choice}
            onValueChange={(next) => {
              setChoice(next);
              save.reset();
            }}
          >
            <SelectTrigger aria-label={ui("Xoá hội thoại sau")} className="w-44">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value={OFF}>{ui("Không tự xoá")}</SelectItem>
              {PRESETS.map((value) => (
                <SelectItem key={value} value={String(value)}>
                  {ui("{{count}} ngày", { count: value })}
                </SelectItem>
              ))}
              <SelectItem value={CUSTOM}>{ui("Số ngày khác…")}</SelectItem>
            </SelectContent>
          </Select>
        }
      />
      {choice === CUSTOM && (
        <div className="px-4 py-3">
          <Field data-invalid={!valid || undefined}>
            <FieldLabel htmlFor="retention-days">{ui("Số ngày không hoạt động")}</FieldLabel>
            <Input
              id="retention-days"
              type="number"
              min={1}
              max={MAX_DAYS}
              value={custom}
              aria-invalid={!valid || undefined}
              className="w-40"
              onChange={(event) => setCustom(event.target.value)}
            />
            {!valid && (
              <FieldError>{ui("Số ngày phải từ 1 đến {{max}}.", { max: MAX_DAYS })}</FieldError>
            )}
          </Field>
        </div>
      )}
      <div className="flex flex-col gap-3 px-4 py-3">
        {days !== null && preview.isSuccess && (
          <Alert variant={affected > 0 ? "destructive" : "default"}>
            <AlertTitle>
              {affected > 0
                ? ui("{{count}} hội thoại sẽ bị xoá khi lưu.", { count: affected })
                : ui("Không có hội thoại nào bị xoá ngay.")}
            </AlertTitle>
          </Alert>
        )}
        {save.isError && (
          <Alert variant="destructive">
            <AlertTitle>{actionErrorText(save.error)}</AlertTitle>
          </Alert>
        )}
        {save.isSuccess && (
          <Alert variant="success">
            <AlertTitle>{ui("Đã lưu thiết lập tự xoá.")}</AlertTitle>
          </Alert>
        )}
        <ConfirmDialog
          trigger={
            <Button className="self-start" disabled={unchanged || (choice === CUSTOM && !valid)}>
              {ui("Lưu thiết lập")}
            </Button>
          }
          title={days === null ? ui("Tắt tự xoá hội thoại?") : ui("Bật tự xoá hội thoại?")}
          description={
            affected > 0
              ? ui("{{count}} hội thoại đã quá hạn sẽ bị xoá ngay khi lưu. Không thể hoàn tác.", {
                  count: affected,
                })
              : ui("Từ giờ hội thoại không có hoạt động quá số ngày này sẽ bị xoá.")
          }
          confirmLabel={ui("Lưu thiết lập")}
          pendingLabel={ui("Đang lưu…")}
          confirmTone={affected > 0 ? "danger" : "default"}
          onConfirm={async () => {
            // Leaving the field out is what clears the policy; there is no partial update to confuse it with.
            await save.mutateAsync({
              body: days === null ? {} : { days },
              signal: AbortSignal.timeout(30000),
            });
          }}
        />
      </div>
    </SettingRows>
  );
}
