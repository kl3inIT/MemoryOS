import * as React from "react";
import { cva, type VariantProps } from "class-variance-authority";
import { cn } from "@/lib/utils";
import { Tabs as TabsPrimitive } from "radix-ui";

function Tabs({
  className,
  orientation = "horizontal",
  ...props
}: React.ComponentProps<typeof TabsPrimitive.Root>) {
  return (
    <TabsPrimitive.Root
      data-slot="tabs"
      data-orientation={orientation}
      className={cn("group/tabs flex gap-2 data-horizontal:flex-col", className)}
      {...props}
    />
  );
}

const tabsListVariants = cva(
  "group/tabs-list inline-flex w-fit items-center justify-center rounded-lg p-[3px] text-muted-foreground group-data-horizontal/tabs:h-8 group-data-vertical/tabs:h-fit group-data-vertical/tabs:flex-col data-[variant=line]:rounded-none",
  {
    variants: {
      variant: {
        default: "bg-muted",
        line: "gap-1 bg-transparent",
      },
    },
    defaultVariants: {
      variant: "default",
    },
  },
);

function TabsList({
  className,
  variant = "default",
  slider = false,
  children,
  ...props
}: React.ComponentProps<typeof TabsPrimitive.List> &
  VariantProps<typeof tabsListVariants> & {
    /** Draws the underline of a `line` list as one bar that slides from the old tab to the new one, past those between. */
    slider?: boolean;
  }) {
  const list = React.useRef<HTMLDivElement>(null);
  const bar = React.useRef<HTMLSpanElement>(null);
  React.useLayoutEffect(() => {
    const row = list.current;
    const mark = bar.current;
    if (!slider || !row || !mark) return;
    const place = () => {
      const active = row.querySelector<HTMLElement>('[role="tab"][data-state="active"]');
      mark.style.opacity = active ? "1" : "0";
      if (!active) return;
      mark.style.width = `${active.offsetWidth}px`;
      // The bar sits where a tab draws its own underline: 3px below the tab, 2px thick.
      mark.style.transform = `translate(${active.offsetLeft}px, ${active.offsetTop + active.offsetHeight + 3}px)`;
    };
    place();
    // The first placement must not slide in from the corner.
    const ready = requestAnimationFrame(() => mark.setAttribute("data-ready", ""));
    const changes = new MutationObserver(place);
    changes.observe(row, { attributes: true, attributeFilter: ["data-state"], subtree: true });
    const resizes = new ResizeObserver(place);
    resizes.observe(row);
    return () => {
      cancelAnimationFrame(ready);
      changes.disconnect();
      resizes.disconnect();
    };
  }, [slider]);
  return (
    <TabsPrimitive.List
      ref={list}
      data-slot="tabs-list"
      data-variant={variant}
      data-slider={slider || undefined}
      className={cn(tabsListVariants({ variant }), slider && "relative", className)}
      {...props}
    >
      {children}
      {slider && (
        <span
          ref={bar}
          aria-hidden="true"
          className="pointer-events-none absolute top-0 left-0 h-0.5 bg-foreground opacity-0 data-ready:transition-[transform,width] data-ready:duration-300 data-ready:ease-out motion-reduce:transition-none"
        />
      )}
    </TabsPrimitive.List>
  );
}

function TabsTrigger({
  className,
  focus = "ring",
  ...props
}: React.ComponentProps<typeof TabsPrimitive.Trigger> & {
  /** `soft` shows the focus as a button does: a faint ring and no dark border or outline. */
  focus?: "ring" | "soft";
}) {
  return (
    <TabsPrimitive.Trigger
      data-slot="tabs-trigger"
      className={cn(
        "relative inline-flex h-[calc(100%-1px)] flex-1 items-center justify-center gap-1.5 rounded-md border border-transparent px-1.5 py-0.5 text-sm font-medium whitespace-nowrap text-foreground/60 transition-all group-data-vertical/tabs:w-full group-data-vertical/tabs:justify-start hover:text-foreground focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-1 focus-visible:outline-ring disabled:pointer-events-none disabled:opacity-50 has-data-[icon=inline-end]:pr-1 has-data-[icon=inline-start]:pl-1 dark:text-muted-foreground dark:hover:text-foreground group-data-[variant=default]/tabs-list:data-active:shadow-sm group-data-[variant=line]/tabs-list:data-active:shadow-none [&_svg]:pointer-events-none [&_svg]:shrink-0 [&_svg:not([class*='size-'])]:size-4",
        "group-data-[variant=line]/tabs-list:bg-transparent group-data-[variant=line]/tabs-list:data-active:bg-transparent dark:group-data-[variant=line]/tabs-list:data-active:border-transparent dark:group-data-[variant=line]/tabs-list:data-active:bg-transparent",
        "data-active:bg-background data-active:text-foreground dark:data-active:border-input dark:data-active:bg-input/30 dark:data-active:text-foreground",
        "after:absolute after:bg-foreground after:opacity-0 after:transition-opacity group-data-horizontal/tabs:after:inset-x-0 group-data-horizontal/tabs:after:bottom-[-5px] group-data-horizontal/tabs:after:h-0.5 group-data-vertical/tabs:after:inset-y-0 group-data-vertical/tabs:after:-right-1 group-data-vertical/tabs:after:w-0.5 group-data-[slider=true]/tabs-list:data-active:after:opacity-0! group-data-[variant=line]/tabs-list:data-active:after:opacity-100",
        focus === "soft" &&
          "focus-visible:border-transparent focus-visible:ring-focus-ring/40 focus-visible:outline-0",
        className,
      )}
      {...props}
    />
  );
}

/** The most tabs a slide counts, so a far jump does not throw the panel across the page. */
const MAX_SLIDE_TABS = 4;

function TabsContent({
  className,
  animated = false,
  ...props
}: React.ComponentProps<typeof TabsPrimitive.Content> & {
  /**
   * Slides the panel in from the side of the tab that was chosen, a step of 32px and 50ms for every tab it passed, and
   * fades it in; the first panel only fades. A reduced-motion setting turns it off.
   */
  animated?: boolean;
}) {
  const panel = React.useRef<HTMLDivElement>(null);
  // Runs when the panel is shown (it mounts then), before it paints, so the first frame already has its start.
  React.useLayoutEffect(() => {
    const el = panel.current;
    const root = el?.closest<HTMLElement>('[data-slot="tabs"]');
    if (!animated || !el || !root) return;
    const tabs = [...root.querySelectorAll<HTMLElement>('[role="tab"]')];
    const at = tabs.findIndex((tab) => tab.getAttribute("data-state") === "active");
    const before = Number(root.dataset.shownTab ?? at);
    root.dataset.shownTab = String(at);
    const passed = Math.min(Math.abs(at - before), MAX_SLIDE_TABS);
    if (passed === 0) return;
    el.style.setProperty("--tw-enter-translate-x", `${Math.sign(at - before) * passed * 32}px`);
    el.style.animationDuration = `${200 + passed * 50}ms`;
  }, [animated]);
  return (
    <TabsPrimitive.Content
      ref={panel}
      data-slot="tabs-content"
      className={cn(
        "flex-1 text-sm outline-none",
        animated &&
          "duration-200 ease-out data-[state=active]:animate-in data-[state=active]:fade-in-0 motion-reduce:animate-none",
        className,
      )}
      {...props}
    />
  );
}

export { Tabs, TabsList, TabsTrigger, TabsContent, tabsListVariants };
