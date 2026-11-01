import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Brain } from "lucide-react";
import { SettingRow, SettingRows } from "@/components/composites/setting-row";
import { Alert, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Skeleton } from "@/components/ui/skeleton";
import { Switch } from "@/components/ui/switch";
import { Textarea } from "@/components/ui/textarea";
import { appText } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { problemOf } from "@/lib/api";
import {
  clearChatMemoryMutation,
  getChatMemoryOptions,
  getChatMemoryQueryKey,
  saveChatMemoryMutation,
  setChatMemoryEnabledMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { ChatMemory } from "@/lib/hey-api/types.gen";

/**
 * What Chat remembers about this member across conversations (MEM-250, Onyx "User Memories"). The whole document
 * is theirs to read and rewrite; the switch saves at once, the text when the field loses focus, as the personal
 * preferences above it do.
 */
export function ChatMemorySection() {
  const ui = useAppTranslation();
  const memory = useQuery(getChatMemoryOptions());

  return (
    <section aria-labelledby="chat-memory-heading" className="flex max-w-2xl flex-col gap-3">
      <h2 id="chat-memory-heading" className="font-heading-h3 text-content-primary">
        {ui("Bộ nhớ")}
      </h2>
      {memory.isPending && <Skeleton className="h-32 w-full" />}
      {memory.isError && (
        <Alert variant="destructive">
          <AlertTitle>{ui("Không tải được bộ nhớ.")}</AlertTitle>
          <Button size="sm" prominence="internal" onClick={() => void memory.refetch()}>
            {ui("Thử lại")}
          </Button>
        </Alert>
      )}
      {memory.isSuccess && <MemoryForm memory={memory.data} />}
    </section>
  );
}

function MemoryForm({ memory }: { memory: ChatMemory }) {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const [typed, setTyped] = useState<string | undefined>(undefined);
  const draft = typed ?? memory.content;
  const stored = (next: ChatMemory) => cache.setQueryData(getChatMemoryQueryKey(), next);

  const enabled = useMutation({ ...setChatMemoryEnabledMutation(), onSuccess: stored });
  const save = useMutation({
    ...saveChatMemoryMutation(),
    onSuccess: stored,
    onError: (error) => {
      // Chat wrote to the memory since this page read it: show what is stored now rather than overwrite it.
      if (problemOf(error)?.status === 409)
        void cache.invalidateQueries({ queryKey: getChatMemoryQueryKey() });
    },
    onSettled: () => setTyped(undefined),
  });
  const clear = useMutation({ ...clearChatMemoryMutation(), onSuccess: stored });
  const conflict = save.isError && problemOf(save.error)?.status === 409;

  return (
    <>
      <SettingRows>
        <SettingRow
          htmlFor="chat-memory-enabled"
          icon={<Brain />}
          title={ui("Ghi nhớ về bạn")}
          control={
            <Switch
              id="chat-memory-enabled"
              checked={memory.enabled}
              disabled={enabled.isPending}
              onCheckedChange={(checked) => enabled.mutate({ body: { enabled: checked } })}
            />
          }
        />
      </SettingRows>
      <Textarea
        id="chat-memory"
        aria-labelledby="chat-memory-heading"
        rows={6}
        maxLength={memory.maxLength}
        value={draft}
        disabled={save.isPending || clear.isPending}
        placeholder={ui("Chưa ghi nhớ điều gì.")}
        onChange={(event) => setTyped(event.target.value)}
        onBlur={() => {
          if (typed === undefined) return;
          if (typed.trim() === memory.content) setTyped(undefined);
          else save.mutate({ body: { content: typed.trim(), revision: memory.revision } });
        }}
      />
      <div className="flex items-center justify-between gap-3">
        <ConfirmDialog
          trigger={
            <Button
              size="sm"
              tone="danger"
              prominence="internal"
              disabled={memory.content.length === 0 || clear.isPending}
            >
              {ui("Xóa bộ nhớ")}
            </Button>
          }
          title={ui("Xóa toàn bộ bộ nhớ?")}
          description={ui("Chat sẽ quên mọi điều đã ghi nhớ về bạn. Không thể hoàn tác.")}
          confirmLabel={ui("Xóa bộ nhớ")}
          pendingLabel={ui("Đang xóa…")}
          onConfirm={async () => {
            await clear.mutateAsync({});
          }}
        />
        <p className="font-secondary-body text-content-muted tabular-nums">
          {ui(appText("{{count}}/{{limit}}", { count: draft.length, limit: memory.maxLength }))}
        </p>
      </div>
      <p aria-live="polite" className="font-secondary-body text-content-muted">
        {save.isPending
          ? ui("Saving…")
          : conflict
            ? ui("Bộ nhớ vừa được Chat cập nhật. Đã tải lại nội dung mới nhất.")
            : save.isError || enabled.isError || clear.isError
              ? ui("Couldn't save. Try again.")
              : ""}
      </p>
    </>
  );
}
