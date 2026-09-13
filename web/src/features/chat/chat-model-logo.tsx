import { Bot } from "lucide-react";
import { OpenAILogo, ClaudeLogo, GeminiLogo } from "@/components/assistant-ui/elements/logos";

export function ChatModelLogo({ modelName }: { modelName: string }) {
  // Branding is display-only; configuration UUIDs still select and authorize the model.
  const name = modelName.split("/").at(-1)?.toLowerCase() ?? "";
  if (/^(gpt-|o\d)/.test(name)) return <OpenAILogo className="size-4" />;
  if (name.startsWith("claude-")) return <ClaudeLogo className="size-4" />;
  if (name.startsWith("gemini-")) return <GeminiLogo className="size-4" />;
  return <Bot aria-hidden="true" className="size-4" />;
}
