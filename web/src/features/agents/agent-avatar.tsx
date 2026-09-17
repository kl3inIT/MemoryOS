import { Bot } from "lucide-react";
import { cn } from "@/lib/utils";
import { agentIconTones, agentIcons } from "./agent-icons";

const boxes = {
  sm: "size-6 rounded-md [&_svg]:size-3.5",
  md: "size-10 rounded-xl [&_svg]:size-5",
  lg: "size-14 rounded-2xl [&_svg]:size-7",
  xl: "size-20 rounded-3xl [&_svg]:size-9",
};

/** The agent's uploaded image, or its icon on a soft tint chosen by topic. */
export function AgentAvatar({
  agent,
  size = "md",
  className,
}: {
  agent: { id: string; name: string; iconName?: string | null; hasAvatar: boolean };
  size?: keyof typeof boxes;
  className?: string;
}) {
  if (agent.hasAvatar)
    return (
      <img
        src={`/api/chat/personas/${agent.id}/avatar`}
        alt=""
        className={cn(boxes[size], "shrink-0 border border-border-subtle object-cover", className)}
      />
    );
  const key = agent.iconName ?? "bot";
  const Icon = agentIcons[key] ?? Bot;
  return (
    <span
      aria-hidden="true"
      className={cn(
        boxes[size],
        "inline-grid shrink-0 place-items-center",
        agentIconTones[key] ?? agentIconTones.bot,
        className,
      )}
    >
      <Icon strokeWidth={1.75} />
    </span>
  );
}
