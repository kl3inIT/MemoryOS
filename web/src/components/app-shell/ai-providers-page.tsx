import { getRouteApi } from "@tanstack/react-router";
import { Sparkles } from "lucide-react";
import { lazy, Suspense, useEffect, useRef, type ComponentType } from "react";
import type { AiProviderTab } from "@/components/app-shell/ai-providers-search";
import { PageHeader, SettingsLayout } from "@/components/composites/settings-layout";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { appText, type AppText } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { cn } from "@/lib/utils";

const providersRoute = getRouteApi("/_authenticated/admin/ai-providers");

type ProviderTab = {
  id: AiProviderTab;
  label: AppText;
  /** Loaded when the tab opens, so one tab never ships the other four. */
  content: ComponentType;
  /** The model catalog needs the wide page; the connection lists read better at the standard width. */
  wide?: boolean;
};

const providerTabs: readonly ProviderTab[] = [
  {
    id: "models",
    label: appText("Mô hình"),
    content: lazy(() =>
      import("@/features/models/models-page").then((module) => ({ default: module.ModelsPage })),
    ),
    wide: true,
  },
  {
    id: "system-one",
    label: appText("Phân loại (System One)"),
    content: lazy(() =>
      import("@/features/system-one/system-one-page").then((module) => ({
        default: module.SystemOnePage,
      })),
    ),
  },
  {
    id: "web-search",
    label: appText("Tìm kiếm Web"),
    content: lazy(() =>
      import("@/features/chat/web-search/chat-web-settings").then((module) => ({
        default: module.ChatWebSettings,
      })),
    ),
  },
  {
    id: "voice",
    label: appText("Giọng nói"),
    content: lazy(() =>
      import("@/features/voice/voice-admin-page").then((module) => ({
        default: module.VoiceAdminPage,
      })),
    ),
  },
  {
    id: "image-generation",
    label: appText("Tạo ảnh"),
    content: lazy(() =>
      import("@/features/chat/image/chat-image-settings").then((module) => ({
        default: module.ChatImageSettings,
      })),
    ),
  },
];

/**
 * `/admin/ai-providers`: every connection to an external AI service, one tab per purpose, as BeyondPilot's AI
 * Providers page. Each tab is an address of its own, so it can be linked and reloaded.
 */
export function AiProvidersPage() {
  const ui = useAppTranslation();
  const { tab } = providersRoute.useSearch();
  const navigate = providersRoute.useNavigate();
  // On a phone the row scrolls, so a tab opened by its address would otherwise sit outside it.
  const tabRow = useRef<HTMLDivElement>(null);
  useEffect(() => {
    tabRow.current
      ?.querySelector<HTMLElement>('[data-state="active"]')
      ?.scrollIntoView?.({ block: "nearest", inline: "nearest" });
  }, [tab]);

  return (
    <SettingsLayout wide>
      <PageHeader title={ui("AI Providers")} icon={<Sparkles />} />
      <Tabs
        value={tab}
        onValueChange={(value) =>
          void navigate({ search: { tab: value as AiProviderTab }, resetScroll: false })
        }
      >
        {/* Five tabs outgrow a phone, so the row scrolls sideways under the same rule. */}
        <div ref={tabRow} className="overflow-x-auto border-b border-border-subtle pb-1.5">
          <TabsList variant="line" aria-label={ui("AI Providers")}>
            {providerTabs.map((each) => (
              <TabsTrigger key={each.id} value={each.id} className="flex-none">
                {ui(each.label)}
              </TabsTrigger>
            ))}
          </TabsList>
        </div>
        {providerTabs.map((each) => (
          <TabsContent key={each.id} value={each.id} className="mt-6">
            <div
              className={cn(
                "flex min-w-0 flex-col gap-8",
                !each.wide && "max-w-(--page-width-standard)",
              )}
            >
              <Suspense fallback={<p role="status">{ui("Đang tải…")}</p>}>
                <each.content />
              </Suspense>
            </div>
          </TabsContent>
        ))}
      </Tabs>
    </SettingsLayout>
  );
}
