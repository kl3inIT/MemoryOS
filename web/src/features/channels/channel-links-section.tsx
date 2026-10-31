import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Check, Copy } from "lucide-react";
import { SettingRow, SettingRows } from "@/components/composites/setting-row";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { IconButton } from "@/components/ui/icon-button";
import { WhatsAppIcon } from "@/components/whatsapp-icon";
import { ZaloIcon } from "@/components/zalo-icon";
import { formatUiMoment } from "@/i18n/format";
import { useAppTranslation } from "@/i18n/use-app-translation";
import {
  createChannelLinkCodeMutation,
  deleteChannelLinkMutation,
  listChannelLinksOptions,
  listChannelLinksQueryKey,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { ChannelLinkCodeResponse, ChannelLinkResponse } from "@/lib/hey-api/types.gen";
import { presentProblem } from "@/lib/problem-presentation";
import { useProblemMessage } from "@/lib/use-problem-message";
import { channelError } from "./channel-errors";
import { linksAccounts, providerWords } from "./channel-provider";

/** While a code is shown, the links are read again so the dialog closes once the bot took the code. */
const WAITING_POLL_MS = 3_000;

/**
 * A member's accounts on the platforms without organisations (Zalo, WhatsApp), one row a bot of the Tenant: a code
 * made here and sent to the bot links the account that sends it (MEM-263). Shown only when the Tenant has such a bot.
 */
export function ChannelLinksSection() {
  const ui = useAppTranslation();
  const problem = useProblemMessage();
  const cache = useQueryClient();
  const [waiting, setWaiting] = useState<{
    link: ChannelLinkResponse;
    code: ChannelLinkCodeResponse;
  } | null>(null);
  const linked = (rows: readonly ChannelLinkResponse[] | undefined, connectionId: string) =>
    Boolean(rows?.find((link) => link.connectionId === connectionId)?.linkedAt);
  const links = useQuery({
    ...listChannelLinksOptions(),
    refetchInterval: (query) =>
      waiting && !linked(query.state.data, waiting.link.connectionId) ? WAITING_POLL_MS : false,
  });
  const reread = () => cache.invalidateQueries({ queryKey: listChannelLinksQueryKey() });
  const makeCode = useMutation({ ...createChannelLinkCodeMutation() });
  const unlink = useMutation({ ...deleteChannelLinkMutation(), onSettled: reread });

  // The bot took the code: the row now says so, and the code is put away.
  if (waiting && linked(links.data, waiting.link.connectionId)) setWaiting(null);

  async function getCode(link: ChannelLinkResponse) {
    const code = await makeCode.mutateAsync({ body: { connectionId: link.connectionId } });
    setWaiting({ link, code });
  }

  if (links.isError) {
    return (
      <Alert variant="destructive" role="alert" className="max-w-2xl">
        <AlertDescription>
          {problem(presentProblem(links.error, "initialLoad").message)}
        </AlertDescription>
      </Alert>
    );
  }
  const rows = links.data?.filter((link) => linksAccounts(link.provider)) ?? [];
  if (rows.length === 0) return null;
  const codeFor = rows.find((link) => link.connectionId === makeCode.variables?.body.connectionId);

  return (
    <section aria-labelledby="channel-links-heading" className="flex max-w-2xl flex-col gap-3">
      <h2 id="channel-links-heading" className="font-heading-h3 text-content-primary">
        {ui("Liên kết tài khoản chat")}
      </h2>
      {makeCode.error ? (
        <Alert variant="destructive" role="alert">
          <AlertDescription>
            {ui(
              channelError(makeCode.error, codeFor?.provider) ??
                problem(presentProblem(makeCode.error, "mutation").message),
            )}
          </AlertDescription>
        </Alert>
      ) : null}
      <SettingRows>
        {rows.map((link) => (
          <SettingRow
            key={link.connectionId}
            icon={link.provider === "WHATSAPP" ? <WhatsAppIcon /> : <ZaloIcon />}
            title={link.botName}
            description={
              link.linkedAt
                ? ui("Đã liên kết · {{time}}", { time: formatUiMoment(link.linkedAt) })
                : ui("Chưa liên kết")
            }
            control={
              link.linkedAt ? (
                <ConfirmDialog
                  trigger={<Button prominence="secondary">{ui("Bỏ liên kết")}</Button>}
                  title={ui("Bỏ liên kết với {{name}}?", { name: link.botName })}
                  description={ui(
                    "Bot sẽ không trả lời tài khoản {{provider}} này nữa cho tới khi bạn liên kết lại.",
                    { provider: providerWords(link.provider).name },
                  )}
                  confirmLabel={ui("Bỏ liên kết")}
                  pendingLabel={ui("Đang bỏ liên kết…")}
                  confirmTone="danger"
                  onConfirm={async () => {
                    await unlink.mutateAsync({ path: { connectionId: link.connectionId } });
                  }}
                  errorMessage={(failure) =>
                    channelError(failure, link.provider) ??
                    presentProblem(failure, "mutation").message
                  }
                />
              ) : (
                <Button
                  prominence="secondary"
                  pending={
                    makeCode.isPending &&
                    makeCode.variables?.body.connectionId === link.connectionId
                  }
                  disabled={makeCode.isPending}
                  onClick={() => void getCode(link).catch(() => undefined)}
                >
                  {ui("Lấy mã")}
                </Button>
              )
            }
          />
        ))}
      </SettingRows>
      {waiting ? (
        <LinkCodeDialog
          botName={waiting.link.botName}
          code={waiting.code}
          renewing={makeCode.isPending}
          onRenew={() => void getCode(waiting.link).catch(() => undefined)}
          onClose={() => {
            setWaiting(null);
            void reread();
          }}
        />
      ) : null}
    </section>
  );
}

/** The code, large and copyable, with the time it has left; a code past its time is replaced by a new one. */
function LinkCodeDialog({
  botName,
  code,
  renewing,
  onRenew,
  onClose,
}: {
  botName: string;
  code: ChannelLinkCodeResponse;
  renewing: boolean;
  onRenew: () => void;
  onClose: () => void;
}) {
  const ui = useAppTranslation();
  const left = useSecondsLeft(code.expiresAt);
  const [copied, setCopied] = useState(false);
  const expired = left <= 0;

  async function copy() {
    try {
      await navigator.clipboard.writeText(code.code);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 2000);
    } catch {
      setCopied(false);
    }
  }

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>{ui("Mã liên kết")}</DialogTitle>
          <DialogDescription>
            {ui("Gửi mã này cho bot {{name}}.", { name: botName })}
          </DialogDescription>
        </DialogHeader>
        <div className="flex items-center justify-center gap-2 rounded-xl border border-border-subtle bg-surface-base px-4 py-5">
          <output
            aria-label={ui("Mã liên kết")}
            className={
              expired
                ? "font-mono text-3xl tracking-widest text-content-muted line-through"
                : "font-mono text-3xl tracking-widest text-content-primary"
            }
          >
            {code.code}
          </output>
          <IconButton
            size="sm"
            prominence="internal"
            aria-label={ui("Sao chép mã")}
            disabled={expired}
            onClick={() => void copy()}
          >
            {copied ? <Check /> : <Copy />}
          </IconButton>
        </div>
        <p role="status" className="text-center font-secondary-body text-content-muted">
          {expired
            ? ui("Mã đã hết hạn.")
            : ui("Hết hạn sau {{time}}", { time: minutesAndSeconds(left) })}
        </p>
        <DialogFooter>
          {expired ? (
            <Button pending={renewing} disabled={renewing} onClick={onRenew}>
              {ui("Lấy mã mới")}
            </Button>
          ) : (
            <Button prominence="secondary" onClick={onClose}>
              {ui("Đóng")}
            </Button>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function useSecondsLeft(expiresAt: string) {
  const until = Date.parse(expiresAt);
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, []);
  return Math.max(0, Math.ceil((until - now) / 1000));
}

function minutesAndSeconds(seconds: number) {
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, "0")}`;
}
