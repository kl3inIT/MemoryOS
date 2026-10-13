import { useState, type ReactNode } from "react";
import { Building2, Clock, FolderOpen, Star, Users } from "lucide-react";
import { Progress } from "@/components/ui/progress";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { TextButton } from "@/components/ui/text-button";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";
import { useGlobalCapability } from "@/features/identity/application-session-context";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { i18n } from "@/i18n/index";
import { fileSize } from "@/lib/file-size";
import type { LibraryView } from "./library-views";
import type { LibraryUsage } from "./storage-meter";

/** Where the storage summary turns into a warning: the owner still has room, but not much. */
const NEARLY_FULL = 90;

type Section = {
  value: string;
  label: string;
  icon: ReactNode;
  /** The views the tab holds; the first opens when the tab is chosen. */
  views: readonly [LibraryView, ...LibraryView[]];
};

/**
 * The library's navigation: one row of tabs under the page title, so the application sidebar stays the only menu
 * beside the page. What the person owns — their files, what is arriving, the trash — is one tab, and so are the
 * organisation's Sources and documents; the views inside such a tab are one choice above its content, as the
 * toolbar's other choices are. The organisation's tab needs Search, so a person without it never sees it. A nearly
 * full storage ends the row. The view on screen is the open tab's panel.
 */
export function LibraryTabs({
  view,
  count,
  usage,
  sources,
  onView,
  onShowLargest,
  children,
}: {
  view: LibraryView;
  /** How many items the view on screen holds, when it counts them. */
  count?: number;
  usage?: LibraryUsage;
  /** Whether the organisation's tab lists its Sources, which the composing page supplies. */
  sources: boolean;
  onView: (next: LibraryView) => void;
  onShowLargest: () => void;
  /** The view on screen. */
  children: ReactNode;
}) {
  const ui = useAppTranslation();
  const canReadDocuments = useGlobalCapability("SEARCH_READ");
  // Whether the pointer chose the focused tab. Clicking a tab that already holds a keyboard ring would keep the ring,
  // so the ring waits for the next key press instead.
  const [byPointer, setByPointer] = useState(false);
  const sections: Section[] = [
    { value: "recent", label: ui("Gần đây"), icon: <Clock />, views: ["recent"] },
    {
      value: "mine",
      label: ui("Của tôi"),
      icon: <FolderOpen />,
      views: ["ready", "pending", "trash"],
    },
    { value: "shared", label: ui("Được chia sẻ"), icon: <Users />, views: ["shared"] },
    { value: "starred", label: ui("Có gắn sao"), icon: <Star />, views: ["starred"] },
    ...(canReadDocuments
      ? [
          {
            value: "organisation",
            label: ui("Tổ chức"),
            icon: <Building2 />,
            views: sources ? (["sources", "documents"] as const) : (["documents"] as const),
          },
        ]
      : []),
  ];
  const viewLabels: Record<LibraryView, string> = {
    recent: ui("Gần đây"),
    ready: ui("Tệp"),
    pending: ui("Đang xử lý"),
    trash: ui("Thùng rác"),
    shared: ui("Được chia sẻ"),
    starred: ui("Có gắn sao"),
    sources: ui("Nguồn dữ liệu"),
    documents: ui("Tài liệu"),
  };
  const section = sections.find((entry) => entry.views.includes(view));

  return (
    <Tabs
      value={section?.value ?? ""}
      onValueChange={(next) => {
        const chosen = sections.find((entry) => entry.value === next);
        if (chosen) onView(chosen.views[0]);
      }}
    >
      <div className="flex flex-col gap-6">
        <div className="flex flex-wrap items-center justify-between gap-x-6 gap-y-2 border-b border-border-subtle pb-1">
          {/* The row fades at its end on a phone, to say it scrolls. */}
          <div
            role="presentation"
            data-pointer={byPointer || undefined}
            onPointerDown={() => setByPointer(true)}
            onKeyDown={() => setByPointer(false)}
            className="max-w-full min-w-0 max-sm:overflow-x-auto max-sm:overflow-y-hidden max-sm:mask-r-from-90% max-sm:pb-1.5 data-pointer:[&_[role=tab]]:border-transparent data-pointer:[&_[role=tab]]:shadow-none data-pointer:[&_[role=tab]]:ring-0 data-pointer:[&_[role=tab]]:outline-none"
          >
            <TabsList
              variant="line"
              aria-label={ui("Phần của thư viện")}
              // On a phone the tabs are one row that scrolls sideways inside the wrapper above, which keeps room for
              // the underline and never scrolls up and down; the row fades at its end to say there is more, and the
              // tabs drop their icons to fit more of it. Wider screens wrap instead and never scroll: the active
              // tab's underline hangs below the list, and `overflow-x-auto` there would turn that overhang into a
              // scrollbar.
              className="h-auto justify-start group-data-horizontal/tabs:h-auto sm:max-w-full sm:flex-wrap max-sm:[&_svg]:hidden"
            >
              {sections.map((entry) => (
                <TabsTrigger
                  key={entry.value}
                  value={entry.value}
                  className="flex-none pointer-coarse:min-h-11"
                >
                  {entry.icon}
                  {entry.label}
                </TabsTrigger>
              ))}
            </TabsList>
          </div>
          {usage && nearlyFull(usage) && (
            <StorageWarning usage={usage} onShowLargest={onShowLargest} />
          )}
        </div>
        {section ? (
          <TabsContent value={section.value}>
            <div className="flex min-w-0 flex-col gap-4">
              {section.views.length > 1 && (
                <ToggleGroup
                  type="single"
                  size="sm"
                  value={view}
                  aria-label={section.label}
                  // Choosing the view on screen again keeps it rather than leaving no view.
                  onValueChange={(next) => next && onView(next as LibraryView)}
                >
                  {section.views.map((entry) => (
                    <ToggleGroupItem key={entry} value={entry} size="sm">
                      {viewLabels[entry]}
                      {entry === view && (count ?? 0) > 0 && (
                        <span className="text-content-muted tabular-nums">{count}</span>
                      )}
                    </ToggleGroupItem>
                  ))}
                </ToggleGroup>
              )}
              {children}
            </div>
          </TabsContent>
        ) : (
          children
        )}
      </div>
    </Tabs>
  );
}

/** Whether the account has used so much of its limit that the list should say so. */
function nearlyFull(usage: LibraryUsage) {
  const limit = usage.limitBytes ?? 0;
  return limit > 0 && (usage.usedBytes / limit) * 100 >= NEARLY_FULL;
}

/**
 * The storage running out, with the one action that helps. With room to spare the figures stay in the library's
 * settings, so the tab row holds the tabs alone.
 */
function StorageWarning({
  usage,
  onShowLargest,
}: {
  usage: LibraryUsage;
  onShowLargest: () => void;
}) {
  const ui = useAppTranslation();
  const limit = usage.limitBytes ?? 0;
  return (
    <section aria-label={ui("Dung lượng đã dùng")} className="flex items-center gap-3">
      <p className="font-secondary-body text-status-danger-content tabular-nums">
        {fileSize(usage.usedBytes, i18n.language)} / {fileSize(limit, i18n.language)}
      </p>
      <Progress
        value={Math.min(100, Math.round((usage.usedBytes / limit) * 100))}
        aria-label={ui("Dung lượng đã dùng")}
        tone="danger"
        className="w-24"
      />
      <TextButton size="sm" onClick={onShowLargest}>
        {ui("Xem tệp lớn nhất")}
      </TextButton>
    </section>
  );
}
