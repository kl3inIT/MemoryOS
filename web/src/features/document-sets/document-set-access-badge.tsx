import { useAppTranslation } from "@/i18n/use-app-translation";
import { Globe, Lock, Users, type LucideIcon } from "lucide-react";
import { StatusBadge, type StatusTone } from "@/components/ui/status-badge";
import { SourceHint } from "@/features/sources/shared/source-hint";
import type { DocumentSet } from "@/features/document-sets/document-sets-api";

type AccessPresentation = { label: string; title: string; tone: StatusTone; icon: LucideIcon };

/** The same pills the Sources table draws, with the tones Sources already gives shared and private access. */
const presentations: Record<"public" | "shared" | "private", AccessPresentation> = {
  public: {
    label: "Công khai",
    title: "Mọi người trong tổ chức dùng được bộ tài liệu này.",
    tone: "success",
    icon: Globe,
  },
  shared: {
    label: "Đã chia sẻ",
    title: "Chỉ những người và nhóm được chia sẻ mới dùng được bộ tài liệu này.",
    tone: "info",
    icon: Users,
  },
  private: {
    label: "Riêng tư",
    title: "Chỉ bạn và quản trị viên trợ lý dùng được bộ tài liệu này.",
    tone: "warning",
    icon: Lock,
  },
};

function documentSetAccess(set: DocumentSet) {
  if (set.isPublic) return "public" as const;
  return set.userShares.length > 0 || set.groupShares.length > 0
    ? ("shared" as const)
    : ("private" as const);
}

export function DocumentSetAccessBadge({ set }: { set: DocumentSet }) {
  const ui = useAppTranslation();
  const presentation = presentations[documentSetAccess(set)];
  const AccessIcon = presentation.icon;

  return (
    <SourceHint hint={ui(presentation.title)}>
      <span className="inline-flex">
        <StatusBadge tone={presentation.tone} variant="pill">
          <AccessIcon aria-hidden="true" />
          {ui(presentation.label)}
        </StatusBadge>
      </span>
    </SourceHint>
  );
}
