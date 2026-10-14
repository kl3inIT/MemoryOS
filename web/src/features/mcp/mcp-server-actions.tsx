import { MoreHorizontal, Pencil, RefreshCw, Trash2 } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { IconButton } from "@/components/ui/icon-button";
import { useAppTranslation } from "@/i18n/use-app-translation";
import type { McpConnection, McpServerView } from "@/lib/hey-api/types.gen";
import { ConnectAction } from "./mcp-connect-actions";
import type { OAuthSetup } from "./mcp-oauth-setup";
import type { ServerAdmin } from "./mcp-server-admin";
import type { serverNextStep } from "./mcp-status";

export type Step = ReturnType<typeof serverNextStep>;

/** Rare actions share one menu. */
export function ServerMenu({
  server,
  step,
  admin,
}: {
  server: McpServerView;
  step: Step;
  admin: ServerAdmin;
}) {
  const ui = useAppTranslation();
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <IconButton
          size="sm"
          prominence="internal"
          aria-label={ui("Thao tác với {{name}}", { name: server.name })}
        >
          <MoreHorizontal />
        </IconButton>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="min-w-44">
        <DropdownMenuGroup>
          <DropdownMenuItem onSelect={() => admin.edit(server)}>
            <Pencil /> {ui("Sửa")}
          </DropdownMenuItem>
          {step === null ? (
            <DropdownMenuItem onSelect={() => admin.refresh(server)}>
              <RefreshCw /> {ui("Lấy lại công cụ")}
            </DropdownMenuItem>
          ) : null}
        </DropdownMenuGroup>
        <DropdownMenuSeparator />
        <DropdownMenuGroup>
          <DropdownMenuItem variant="destructive" onSelect={() => admin.remove(server)}>
            <Trash2 /> {ui("Xóa máy chủ")}
          </DropdownMenuItem>
        </DropdownMenuGroup>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

/**
 * Whatever the server still lacks, as one button. Adding an OAuth application and choosing among several happen on
 * the server's page, so the list passes `onOpenPage` to go there.
 */
export function NextStepAction({
  step,
  server,
  own,
  admin,
  setup,
  onOpenPage,
  primary = false,
}: {
  step: Step;
  server: McpServerView;
  own?: McpConnection;
  admin: ServerAdmin;
  setup: OAuthSetup;
  onOpenPage?: () => void;
  primary?: boolean;
}) {
  const ui = useAppTranslation();
  const apps = setup.clients.data ?? [];
  const prominence = primary ? "primary" : "secondary";
  switch (step) {
    case "ADD_OAUTH_APP":
      // While the result of discovery or the manual form is open, that is where the step continues.
      if (setup.discovery.data || setup.manual) return null;
      return (
        <Button
          size="sm"
          prominence={prominence}
          pending={setup.discovery.isPending}
          onClick={onOpenPage ?? setup.begin}
        >
          {ui("Thêm ứng dụng OAuth")}
        </Button>
      );
    case "CONNECT_SHARED": {
      // A shared API key is entered with the server; a shared OAuth account signs in through an application.
      if (server.authType === "API_TOKEN")
        return (
          <Button size="sm" prominence={prominence} onClick={() => admin.edit(server)}>
            {ui("Nhập khóa API dùng chung")}
          </Button>
        );
      const only = apps.length === 1 ? apps[0] : undefined;
      // With several applications the page lists them, each with its own Connect.
      if (!only && !onOpenPage) return null;
      return (
        <Button
          size="sm"
          prominence={prominence}
          pending={setup.connect.isPending}
          onClick={() =>
            only
              ? setup.connect.mutate({ path: setup.path, body: { oauthClientId: only.id } })
              : onOpenPage?.()
          }
        >
          {ui("Kết nối tài khoản dùng chung")}
        </Button>
      );
    }
    case "CONNECT_OWN":
      return own ? (
        <ConnectAction
          connection={own}
          returnPath="/admin/mcp"
          onApiKey={admin.askApiKey}
          label={ui("Kết nối tài khoản của bạn")}
          primary={primary}
        />
      ) : null;
    case "FETCH_TOOLS":
      return (
        <Button
          size="sm"
          prominence={prominence}
          pending={admin.refreshing(server)}
          onClick={() => admin.refresh(server)}
        >
          <RefreshCw data-icon="inline-start" />
          {ui("Lấy công cụ")}
        </Button>
      );
    default:
      return null;
  }
}

/** The outcome code the OAuth callback appended; it never carries provider text. */
export function ConnectionOutcome({ outcome }: { outcome: string }) {
  const ui = useAppTranslation();
  const copy =
    outcome === "connected"
      ? { variant: "default" as const, text: "Đã kết nối máy chủ MCP." }
      : outcome === "authorization-cancelled"
        ? { variant: "default" as const, text: "Bạn đã hủy việc cấp quyền." }
        : outcome === "issuer-mismatch"
          ? {
              variant: "destructive" as const,
              text: "Máy chủ cấp quyền không khớp cấu hình đã lưu.",
            }
          : outcome === "configuration-changed"
            ? {
                variant: "default" as const,
                text: "Cấu hình đã đổi khi đang cấp quyền. Hãy thử lại.",
              }
            : { variant: "destructive" as const, text: "Cấp quyền không thành công." };
  return (
    <Alert variant={copy.variant} role="status">
      <AlertDescription>{ui(copy.text)}</AlertDescription>
    </Alert>
  );
}
