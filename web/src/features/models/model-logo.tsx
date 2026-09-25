import { Bot } from "lucide-react";
import { OpenAILogo, ClaudeLogo, GeminiLogo } from "@/components/assistant-ui/elements/logos";
import { cn } from "@/lib/utils";
import { modelVendor, vendorFiles } from "./model-vendor";

export function ModelLogo({ modelName, className }: { modelName: string; className?: string }) {
  // Branding is display-only; configuration UUIDs still select and authorize the model.
  const vendor = modelVendor(modelName);
  const size = cn("size-4 shrink-0", className);
  if (vendor === "openai") return <OpenAILogo className={size} />;
  if (vendor === "claude") return <ClaudeLogo className={size} />;
  if (vendor === "gemini") return <GeminiLogo className={size} />;
  if (vendor) {
    const { file, monochrome } = vendorFiles[vendor];
    return (
      <img
        src={`/model-logos/${file}`}
        alt=""
        aria-hidden="true"
        className={cn(size, "object-contain", monochrome && "dark:invert")}
      />
    );
  }
  return <Bot aria-hidden="true" className={size} />;
}
