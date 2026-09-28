import { Users } from "lucide-react";
import { Avatar, AvatarFallback } from "@/components/ui/avatar";

const tones = ["info", "success", "warning", "neutral"] as const;

const avatarSizes = { xs: "xs", sm: "sm", md: "default" } as const;

function initials(name: string) {
  const words = name.trim().split(/\s+/).filter(Boolean);
  const [first, ...rest] = words;
  if (first === undefined) return "?";
  return (first.charAt(0) + (rest.at(-1)?.charAt(0) ?? "")).toLocaleUpperCase();
}

function toneFor(seed: string) {
  let hash = 0;
  for (const char of seed) hash = (hash * 31 + char.charCodeAt(0)) >>> 0;
  return tones[hash % tones.length];
}

/** Initials for a person or a Group icon, tinted deterministically so the same identity keeps its color. */
export function PersonAvatar({
  name,
  seed,
  kind = "person",
  size = "md",
  className,
}: {
  name: string;
  seed?: string;
  kind?: "person" | "group";
  size?: "xs" | "sm" | "md";
  className?: string;
}) {
  return (
    <Avatar aria-hidden="true" size={avatarSizes[size]} className={className}>
      <AvatarFallback tone={kind === "group" ? "neutral" : toneFor(seed ?? name)}>
        {kind === "group" ? <Users /> : initials(name)}
      </AvatarFallback>
    </Avatar>
  );
}
