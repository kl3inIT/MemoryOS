import { Bot } from "lucide-react";
import { agentIcons } from "./agent-icons";
import { cn } from "@/lib/utils";

export function AgentAvatar({
  agent,
  size = "md",
  className,
}: {
  agent: {
    id: string;
    name: string;
    iconName?: string | null;
    hasAvatar: boolean;
    builtin?: boolean;
  };
  size?: "sm" | "md" | "lg";
  className?: string;
}) {
  const Icon = agentIcons[agent.iconName ?? ""] ?? Bot;
  const box =
    size === "sm"
      ? "size-7 rounded-lg"
      : size === "lg"
        ? "size-14 rounded-2xl"
        : "size-10 rounded-xl";
  if (agent.hasAvatar)
    return (
      <img
        src={`/api/chat/personas/${agent.id}/avatar`}
        alt=""
        className={cn(box, "shrink-0 border border-border-subtle object-cover", className)}
      />
    );
  return (
    <span
      aria-hidden="true"
      className={cn(
        box,
        "inline-flex shrink-0 items-center justify-center bg-status-info-surface text-status-info-content",
        className,
      )}
    >
      <Icon className={size === "lg" ? "size-7" : size === "sm" ? "size-4" : "size-5"} />
    </span>
  );
}
