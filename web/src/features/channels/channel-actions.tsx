import { KeyRound, MoreHorizontal, Trash2 } from "lucide-react";
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
import { useAppTranslation } from "@/i18n/use-app-translation";
import type { ChannelResponse } from "@/lib/hey-api/types.gen";
import type { ChannelAdmin } from "./channel-admin";

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
          <DropdownMenuItem onSelect={() => admin.replaceSecret(channel)}>
            <KeyRound /> {ui("Thay App Secret")}
          </DropdownMenuItem>
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
