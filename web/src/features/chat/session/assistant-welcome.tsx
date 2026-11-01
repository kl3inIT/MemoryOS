import type { ReactNode } from "react";
import { AgentAvatar } from "@/features/agents/agent-avatar";

/**
 * The opening of a new topic with an assistant: its face, name and title, then its greeting (its description when
 * it has none) above its starters.
 */
export function AssistantWelcome({
  assistant,
  level = 1,
  children,
}: {
  assistant: {
    id: string;
    name: string;
    title: string;
    description: string;
    greeting: string;
    avatarSeed: string;
    hasAvatar: boolean;
  };
  /** The name's heading level: a page's own topic opens with level 1, a panel inside another page with 2. */
  level?: 1 | 2;
  children: ReactNode;
}) {
  const opening = assistant.greeting || assistant.description;
  const Heading = level === 1 ? "h1" : "h2";
  return (
    <div className="flex flex-col items-center gap-3">
      <AgentAvatar agent={assistant} size="lg" />
      <div className="flex flex-col items-center gap-1">
        <Heading className="text-2xl font-medium tracking-tight text-balance">
          {assistant.name}
        </Heading>
        {assistant.title && <p className="text-content-muted">{assistant.title}</p>}
      </div>
      {opening && (
        <p className="max-w-xl text-pretty whitespace-pre-line text-content-secondary">{opening}</p>
      )}
      {children}
    </div>
  );
}
