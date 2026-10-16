import { cn } from "@/lib/utils";
import { agentFace } from "./agent-face";

const boxes = {
  sm: "size-6 rounded-md",
  /** A two-line sidebar row: name above a preview. */
  row: "size-8 rounded-lg",
  md: "size-10 rounded-xl",
  lg: "size-14 rounded-2xl",
  xl: "size-20 rounded-3xl",
};

/** The agent's uploaded image, or else the face drawn from its seed. */
export function AgentAvatar({
  agent,
  size = "md",
  className,
}: {
  agent: { id: string; avatarSeed: string; hasAvatar: boolean };
  size?: keyof typeof boxes;
  className?: string;
}) {
  return (
    <img
      src={agent.hasAvatar ? `/api/chat/personas/${agent.id}/avatar` : agentFace(agent.avatarSeed)}
      alt=""
      className={cn(
        boxes[size],
        "shrink-0 object-cover",
        agent.hasAvatar && "border border-border-subtle",
        className,
      )}
    />
  );
}
