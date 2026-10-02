import { Cable } from "lucide-react";
import { ProviderLogo } from "@/components/provider-logos/provider-logo";

/** The mark of an assistant that reads MemoryOS: Claude, ChatGPT, or a plug for any other app. */
export function McpClientMark({
  client,
  className,
}: {
  client: "CLAUDE" | "CHATGPT" | "CUSTOM" | "OTHER";
  className?: string;
}) {
  if (client === "CLAUDE") return <ProviderLogo mark="ANTHROPIC" className={className} />;
  if (client === "CHATGPT") return <ProviderLogo mark="OPENAI" className={className} />;
  return <Cable aria-hidden="true" className={className} />;
}
