import { useEffect, useId, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { RefreshCw } from "lucide-react";
import { SectionHeader } from "@/components/composites/section-header";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { useAppTranslation } from "@/i18n/use-app-translation";
import {
  getChannelPairingOptions,
  listChannelsQueryKey,
  relinkChannelMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { ChannelResponse } from "@/lib/hey-api/types.gen";
import { presentProblem } from "@/lib/problem-presentation";
import { useProblemMessage } from "@/lib/use-problem-message";
import { channelError } from "./channel-errors";
import { channelFailure, needsRelink } from "./channel-status";

/** WhatsApp shows a new code about every 20 seconds, so the one shown is read again well within that. */
const PAIRING_POLL_MS = 2_000;
const QR_SIZE = 224;

/**
 * Linking a WhatsApp bot, as WhatsApp Web links a computer: the code to scan with the bot's own phone while the bot
 * shows one, or linking it again once WhatsApp ended its session (MEM-283).
 */
export function ChannelPairingSection({ channel }: { channel: ChannelResponse }) {
  const ui = useAppTranslation();
  const problem = useProblemMessage();
  const cache = useQueryClient();
  const heading = useId();
  const pairing = channel.status === "PAIRING";
  const shown = useQuery({
    ...getChannelPairingOptions({ path: { channelId: channel.id } }),
    enabled: pairing,
    refetchInterval: pairing ? PAIRING_POLL_MS : false,
  });
  const relink = useMutation({
    ...relinkChannelMutation(),
    onSettled: () => cache.invalidateQueries({ queryKey: listChannelsQueryKey() }),
  });
  const relinking = needsRelink(channel);
  if (channel.provider !== "WHATSAPP" || !(pairing || relinking)) return null;
  const code = pairing && shown.data && "code" in shown.data ? shown.data.code : undefined;

  return (
    <section aria-labelledby={heading} className="flex flex-col gap-3">
      <SectionHeader id={heading} level="group" title={ui("Liên kết WhatsApp")} />
      {relink.error ? (
        <Alert variant="destructive" role="alert">
          <AlertDescription>
            {ui(
              channelError(relink.error, "WHATSAPP") ??
                problem(presentProblem(relink.error, "mutation").message),
            )}
          </AlertDescription>
        </Alert>
      ) : null}
      {pairing ? (
        <div className="flex flex-wrap items-center gap-6 rounded-xl border border-border-subtle p-4">
          <div className="mx-auto grid size-60 shrink-0 place-items-center rounded-xl bg-white ring-1 ring-black/10 sm:mx-0">
            {code ? (
              <PairingCode code={code} label={ui("Mã QR liên kết WhatsApp")} />
            ) : (
              <Skeleton className="size-56" />
            )}
          </div>
          <ol className="flex min-w-56 flex-1 list-decimal flex-col gap-2 pl-5 font-secondary-body text-content-secondary">
            <li>
              {ui("Mở WhatsApp trên điện thoại của số +{{phone}}.", { phone: channel.appId })}
            </li>
            <li>{ui("Vào Cài đặt › Thiết bị liên kết › Liên kết thiết bị.")}</li>
            <li>{ui("Quét mã này. Mã tự đổi sau vài giây.")}</li>
          </ol>
        </div>
      ) : (
        <div className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-border-subtle px-4 py-3">
          <span className="font-secondary-body text-status-danger-content">
            {ui(channelFailure(channel) ?? "")}
          </span>
          <Button
            size="sm"
            prominence="secondary"
            pending={relink.isPending}
            disabled={relink.isPending}
            onClick={() =>
              relink.mutate({
                path: { channelId: channel.id },
                headers: { "If-Match": `"${channel.revision}"` },
              })
            }
          >
            <RefreshCw data-icon="inline-start" />
            {ui("Liên kết lại")}
          </Button>
        </div>
      )}
    </section>
  );
}

/**
 * The code drawn as a QR code; the library loads only when a bot shows one. A library that fails to load stays failed
 * until the page is loaded again, so a failure says so rather than wait; the code as text would not help, since
 * WhatsApp links only by scanning.
 */
function PairingCode({ code, label }: { code: string; label: string }) {
  const ui = useAppTranslation();
  const canvas = useRef<HTMLCanvasElement>(null);
  const [state, setState] = useState<"drawing" | "drawn" | "failed">("drawing");
  useEffect(() => {
    let current = true;
    import("qrcode")
      .then(({ toCanvas }) => {
        if (!current || !canvas.current) return;
        return toCanvas(canvas.current, code, { width: QR_SIZE, margin: 1 }).then(() => {
          if (current) setState("drawn");
        });
      })
      .catch(() => {
        if (current) setState("failed");
      });
    return () => {
      current = false;
    };
  }, [code]);
  return (
    <>
      <canvas ref={canvas} role="img" aria-label={label} hidden={state !== "drawn"} />
      {state === "drawing" ? <Skeleton className="size-56" /> : null}
      {state === "failed" ? (
        <p role="alert" className="px-4 text-center font-secondary-body text-status-danger-content">
          {ui("Không hiện được mã QR. Hãy tải lại trang.")}
        </p>
      ) : null}
    </>
  );
}
