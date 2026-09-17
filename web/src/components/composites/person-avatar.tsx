import { Users } from "lucide-react";
import { cn } from "@/lib/utils";

const tones = [
  "bg-status-info-surface text-status-info-content",
  "bg-status-success-surface text-status-success-content",
  "bg-status-warning-surface text-status-warning-content",
  "bg-surface-sunken text-content-secondary",
];

function initials(name: string) {
  const words = name.trim().split(/\s+/).filter(Boolean);
  if (words.length === 0) return "?";
  const first = words[0]!.charAt(0);
  const last = words.length > 1 ? words[words.length - 1]!.charAt(0) : "";
  return (first + last).toLocaleUpperCase();
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
  const box =
    size === "xs"
      ? "size-4 text-[0.5rem]"
      : size === "sm"
        ? "size-6 text-[0.625rem]"
        : "size-8 text-xs";
  return (
    <span
      aria-hidden="true"
      className={cn(
        "inline-grid shrink-0 place-items-center rounded-full font-semibold",
        box,
        kind === "group" ? "bg-surface-sunken text-content-secondary" : toneFor(seed ?? name),
        className,
      )}
    >
      {kind === "group" ? (
        <Users className={size === "md" ? "size-4" : "size-3"} />
      ) : (
        initials(name)
      )}
    </span>
  );
}
