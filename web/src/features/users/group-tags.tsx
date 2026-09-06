import { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";
import { Badge } from "@/components/ui/badge";
import type { UserGroupOption } from "./user-groups-dialog";

type GroupTagsProps = {
  groups: readonly UserGroupOption[];
  editable: boolean;
  userLabel: string;
  onEdit: (returnTarget: HTMLElement) => void;
};

export function GroupTags({ groups, editable, userLabel, onEdit }: GroupTagsProps) {
  const containerRef = useRef<HTMLElement | null>(null);
  const previousWidthRef = useRef(0);
  const [measuredGroups, setMeasuredGroups] = useState(groups);
  const [measuredVisibleCount, setVisibleCount] = useState<number | null>(null);
  if (measuredGroups !== groups) {
    setMeasuredGroups(groups);
    setVisibleCount(null);
  }
  const visibleCount =
    typeof ResizeObserver === "undefined" ? Math.min(groups.length, 2) : measuredVisibleCount;

  const measure = useCallback(() => {
    const container = containerRef.current;
    if (!container || groups.length < 2) {
      setVisibleCount(groups.length);
      return;
    }
    const tags = container.querySelectorAll<HTMLElement>("[data-group-tag]");
    if (tags.length === 0) return;
    const available = container.clientWidth;
    const gap = 4;
    const overflowBadgeWidth = 38;
    let used = 0;
    let count = 0;
    for (let index = 0; index < tags.length; index += 1) {
      const tag = tags[index];
      if (!tag) continue;
      const gapBefore = count === 0 ? 0 : gap;
      const reserve = index < tags.length - 1 ? gap + overflowBadgeWidth : 0;
      if (used + gapBefore + tag.offsetWidth + reserve > available) break;
      used += gapBefore + tag.offsetWidth;
      count += 1;
    }
    setVisibleCount(Math.max(1, count));
  }, [groups.length]);

  useLayoutEffect(() => {
    if (visibleCount === null) measure();
  }, [measure, visibleCount]);
  useEffect(() => {
    if (typeof ResizeObserver === "undefined") return;
    const container = containerRef.current;
    if (!container) return;
    const observer = new ResizeObserver((entries) => {
      const width = entries[0]?.contentRect.width ?? 0;
      if (Math.abs(width - previousWidthRef.current) < 1) return;
      previousWidthRef.current = width;
      setVisibleCount(null);
    });
    observer.observe(container);
    return () => observer.disconnect();
  }, [groups.length]);

  const measuring = visibleCount === null;
  const shown = measuring ? groups : groups.slice(0, visibleCount);
  const overflow = groups.length - (visibleCount ?? groups.length);
  const hiddenNames = groups.slice(visibleCount ?? groups.length).map((group) => group.name);
  const className =
    "flex h-8 w-full min-w-0 items-center gap-1 overflow-hidden rounded-lg text-left outline-none focus-visible:ring-3 focus-visible:ring-focus-ring/40";
  const content =
    groups.length === 0 ? (
      <span className="font-main-ui-body text-content-muted">—</span>
    ) : (
      <>
        {shown.map((group) => (
          <Badge
            key={group.id}
            data-group-tag
            variant="secondary"
            className="max-w-32 shrink-0 border border-border-subtle bg-surface-subtle text-content-secondary"
            title={group.systemKey ? `${group.name} · system group` : group.name}
          >
            <span className="truncate">{group.name}</span>
          </Badge>
        ))}
        {!measuring && overflow > 0 ? (
          <Badge
            variant="outline"
            className="shrink-0 bg-surface-raised text-content-muted"
            title={hiddenNames.join(", ")}
            aria-label={`${overflow} more groups: ${hiddenNames.join(", ")}`}
          >
            +{overflow}
          </Badge>
        ) : null}
      </>
    );

  if (editable) {
    return (
      <button
        ref={(node) => {
          containerRef.current = node;
        }}
        type="button"
        aria-label={`Edit groups for ${userLabel}`}
        className={`${className} hover:bg-surface-subtle`}
        onClick={(event) => onEdit(event.currentTarget)}
      >
        {content}
      </button>
    );
  }

  return (
    <div
      ref={(node) => {
        containerRef.current = node;
      }}
      className={className}
      aria-label={groups.length === 0 ? "No groups" : groups.map((group) => group.name).join(", ")}
    >
      {content}
    </div>
  );
}
