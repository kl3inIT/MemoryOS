import { useAppTranslation } from "@/i18n/use-app-translation";
import { ArrowRight, Globe, Lock, Pencil, Pin, Share2, Star, Users } from "lucide-react";
import { hoverReveal } from "@/components/composites/hover-reveal";
import { PersonAvatar } from "@/components/composites/person-avatar";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardFooter } from "@/components/ui/card";
import { IconButton } from "@/components/ui/icon-button";
import { agentVisibility, type Persona } from "@/features/chat/chat-personas-api";
import { can } from "@/lib/resource-permissions";
import { cn } from "@/lib/utils";
import { AgentActions } from "./agent-actions";
import { AgentAvatar } from "./agent-avatar";

/** One agent in the catalog: open, edit, share, pin and start a conversation (Langdock, Onyx). */
export function AgentCard({
  agent,
  owner,
  pending,
  onStart,
  onOpen,
  onEdit,
  onShare,
  onPin,
}: {
  agent: Persona;
  owner: string;
  pending: boolean;
  onStart: () => void;
  onOpen: () => void;
  onEdit: () => void;
  onShare: () => void;
  onPin: () => void;
}) {
  const ui = useAppTranslation();
  const visibility = agentVisibility(agent);
  const VisibilityIcon = visibility === "public" ? Globe : visibility === "shared" ? Users : Lock;
  return (
    <article className="group relative min-w-0 rounded-2xl transition-shadow duration-150 hover:shadow-hover">
      <Card size="sm" className="h-full">
        <CardContent className="flex flex-1">
          <div className="flex min-w-0 flex-1 gap-3">
            <AgentAvatar agent={agent} />
            <div className="min-w-0 flex-1">
              <div className="flex min-w-0 items-start gap-2 pr-7">
                <h3 className="min-w-0 font-main-content-body text-content-primary">
                  <button
                    type="button"
                    onClick={onOpen}
                    className="line-clamp-2 text-left outline-none after:absolute after:inset-0 after:rounded-2xl focus-visible:after:ring-3 focus-visible:after:ring-focus-ring/40"
                  >
                    {agent.name}
                  </button>
                </h3>
                {agent.featured && (
                  <span className="inline-flex h-5 shrink-0 items-center gap-1 rounded-full bg-status-warning-surface px-1.5 font-secondary-action text-status-warning-content">
                    <Star aria-hidden="true" className="size-3 fill-current" />
                    {ui("Nổi bật")}
                  </span>
                )}
              </div>
              <p className="mt-1 line-clamp-2 font-secondary-body text-content-muted">
                {agent.description || ui("Chưa có mô tả.")}
              </p>
            </div>
          </div>
        </CardContent>
        <CardFooter className="relative z-10 mt-auto">
          <div className="flex min-w-0 flex-1 items-center gap-3">
            <span className="flex min-w-0 flex-1 items-center gap-2 font-secondary-body text-content-muted">
              <PersonAvatar
                name={owner}
                seed={agent.owner.actor?.actorId ?? agent.owner.group?.id ?? owner}
                kind={agent.owner.group ? "group" : "person"}
                size="xs"
              />
              <span className="truncate">{owner}</span>
              <span aria-hidden="true">·</span>
              <VisibilityIcon aria-hidden="true" className="size-3 shrink-0" />
              <span className="shrink-0">
                {visibility === "public"
                  ? ui("Công khai")
                  : visibility === "shared"
                    ? ui("Đã chia sẻ")
                    : ui("Riêng tư")}
              </span>
            </span>
            <Button size="sm" prominence="tertiary" pending={pending} onClick={onStart}>
              {ui("Bắt đầu chat")}
              <ArrowRight data-icon="inline-end" aria-hidden="true" />
            </Button>
          </div>
        </CardFooter>
      </Card>
      <div className="absolute top-3 right-3 z-10 flex items-center gap-0.5">
        <div
          className={cn(
            "flex items-center gap-0.5 rounded-lg bg-surface-raised shadow-hover",
            hoverReveal,
          )}
        >
          {can(agent, "edit") && (
            <IconButton
              size="sm"
              prominence="tertiary"
              aria-label={ui("Sửa {{v1}}", { v1: agent.name })}
              title={ui("Sửa trợ lý")}
              onClick={onEdit}
            >
              <Pencil />
            </IconButton>
          )}
          {can(agent, "share") && (
            <IconButton
              size="sm"
              prominence="tertiary"
              aria-label={ui("Chia sẻ {{v1}}", { v1: agent.name })}
              title={ui("Chia sẻ trợ lý")}
              onClick={onShare}
            >
              <Share2 />
            </IconButton>
          )}
          <AgentActions agent={agent} />
        </div>
        {!agent.builtin && (
          <span className={cn("flex", !agent.pinned && hoverReveal)}>
            <IconButton
              size="sm"
              prominence="tertiary"
              aria-label={agent.pinned ? ui("Bỏ ghim") : ui("Ghim vào thanh bên")}
              title={agent.pinned ? ui("Bỏ ghim") : ui("Ghim vào thanh bên")}
              aria-pressed={agent.pinned}
              onClick={onPin}
            >
              <Pin className={cn(agent.pinned && "fill-current")} />
            </IconButton>
          </span>
        )}
      </div>
    </article>
  );
}
