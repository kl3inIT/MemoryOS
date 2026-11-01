import { Link } from "@tanstack/react-router";
import { KeyRound, Link2, MoreHorizontal, Trash2 } from "lucide-react";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { IconButton } from "@/components/ui/icon-button";
import { Switch } from "@/components/ui/switch";
import { LarkIcon } from "@/components/lark-icon";
import { WhatsAppIcon } from "@/components/whatsapp-icon";
import { ZaloIcon } from "@/components/zalo-icon";
import { useAppTranslation } from "@/i18n/use-app-translation";
import type { ChannelResponse } from "@/lib/hey-api/types.gen";
import type { ChannelAdmin } from "./channel-admin";
import { linksAccounts } from "./channel-provider";

/** Switches a bot on or off; saved on change. */
export function ChannelSwitch({
  channel,
  admin,
}: {
  channel: ChannelResponse;
  admin: ChannelAdmin;
}) {
  const ui = useAppTranslation();
  return (
    <Switch
      checked={channel.enabled}
      disabled={admin.switching(channel)}
      aria-label={ui(channel.enabled ? "Tắt {{name}}" : "Bật {{name}}", { name: channel.name })}
      onCheckedChange={(checked) => admin.setEnabled(channel, checked)}
    />
  );
}

/** Rare actions share one menu. */
export function ChannelMenu({ channel, admin }: { channel: ChannelResponse; admin: ChannelAdmin }) {
  const ui = useAppTranslation();
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <IconButton
          size="sm"
          prominence="internal"
          aria-label={ui("Thao tác với {{name}}", { name: channel.name })}
        >
          <MoreHorizontal />
        </IconButton>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="min-w-44">
        <DropdownMenuGroup>
          {/* A WhatsApp bot keeps no secret: it is linked by scanning its code. */}
          {channel.provider === "WHATSAPP" ? null : (
            <DropdownMenuItem onSelect={() => admin.replaceSecret(channel)}>
              <KeyRound />{" "}
              {channel.provider === "ZALO_BOT" ? ui("Thay token") : ui("Thay App Secret")}
            </DropdownMenuItem>
          )}
          {linksAccounts(channel.provider) ? (
            <DropdownMenuItem asChild>
              <Link to="/settings/chat">
                <Link2 /> {ui("Liên kết tài khoản của bạn")}
              </Link>
            </DropdownMenuItem>
          ) : null}
        </DropdownMenuGroup>
        <DropdownMenuSeparator />
        <DropdownMenuGroup>
          <DropdownMenuItem variant="destructive" onSelect={() => admin.remove(channel)}>
            <Trash2 /> {ui("Xóa bot")}
          </DropdownMenuItem>
        </DropdownMenuGroup>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

/** The platform's own mark on a white tile, as the MCP list shows a service's own logo. */
export function ChannelMark({ provider }: { provider: ChannelResponse["provider"] }) {
  return (
    <span
      className="grid size-10 shrink-0 place-items-center rounded-xl bg-white ring-1 ring-black/10"
      aria-hidden="true"
    >
      {provider === "ZALO_BOT" ? (
        <ZaloIcon className="size-7" />
      ) : provider === "WHATSAPP" ? (
        <WhatsAppIcon className="size-6" />
      ) : (
        <LarkIcon className="size-6" />
      )}
    </span>
  );
}
