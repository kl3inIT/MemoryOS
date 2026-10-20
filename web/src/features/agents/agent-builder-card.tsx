import { use, useSyncExternalStore, type ReactNode } from "react";
import { useAuiState } from "@assistant-ui/react";
import { Button } from "@/components/ui/button";
import { REASONING_EFFORTS } from "@/features/chat/chat-models";
import { agentTools, type AgentTool } from "@/features/chat/chat-personas-api";
import { useAppTranslation } from "@/i18n/use-app-translation";
import type { BuilderField, FieldChange } from "./agent-builder-changes";
import { BuilderCard, type BuilderCardContext } from "./agent-builder-context";
import { useToolNames } from "./agent-tools";

/**
 * MEM-280: under a builder reply, one card for each change it made to the form, as LobeHub's `ConfigDiffView`: a row
 * per field with before → after, the instructions as their line count (the text is in the form), and *Hoàn tác*.
 */
export function AgentBuilderChangeCards() {
  const context = use(BuilderCard);
  const ids = useAuiState(
    (state) => state.message.metadata.custom.assistantChanges as string[] | undefined,
  );
  if (!context || !ids?.length) return null;
  return (
    <div className="mt-3 flex flex-col gap-3">
      {ids.map((id) => (
        <ChangeCard key={id} id={id} context={context} />
      ))}
    </div>
  );
}

function ChangeCard({ id, context }: { id: string; context: BuilderCardContext }) {
  const ui = useAppTranslation();
  const labels = useFieldLabels();
  useSyncExternalStore(context.changes.subscribe, context.changes.getVersion);
  const change = context.changes.get(id);
  if (!change || change.fields.length === 0) return null;
  return (
    <section
      aria-label={ui("Đã sửa bản nháp")}
      className="overflow-hidden rounded-xl border border-border-subtle bg-surface-raised"
    >
      <header className="flex items-center gap-2 border-b border-border-subtle px-4 py-2.5">
        <h3 className="font-main-ui-action text-content-primary">
          {change.undone ? ui("Đã hoàn tác") : ui("Đã sửa bản nháp")}
        </h3>
        <span className="font-secondary-body text-content-muted">
          {ui("{{count}} mục", { count: change.fields.length })}
        </span>
        {!change.undone && (
          <Button
            type="button"
            size="sm"
            prominence="tertiary"
            className="ml-auto"
            onClick={() => context.changes.undo(id)}
          >
            {ui("Hoàn tác")}
          </Button>
        )}
      </header>
      <dl className={change.undone ? "opacity-60" : undefined}>
        {change.fields.map((field) => (
          <div
            key={field.field}
            className="flex flex-col gap-0.5 border-b border-border-subtle px-4 py-2.5 last:border-b-0"
          >
            <dt className="font-secondary-body text-content-muted">{labels[field.field]}</dt>
            <dd className="font-secondary-body text-content-primary">
              <FieldValue change={field} nameOf={context.nameOf} />
            </dd>
          </div>
        ))}
      </dl>
    </section>
  );
}

function useFieldLabels(): Record<BuilderField, string> {
  const ui = useAppTranslation();
  return {
    name: ui("Tên trợ lý"),
    title: ui("Chức danh"),
    description: ui("Mô tả"),
    avatarSeed: ui("Ảnh đại diện"),
    greeting: ui("Lời chào"),
    starterPrompts: ui("Câu hỏi gợi ý"),
    tools: ui("Công cụ"),
    sourceIds: ui("Nguồn"),
    documentSetIds: ui("Bộ tài liệu"),
    mcpServerIds: ui("Ứng dụng MCP"),
    modelConfigurationId: ui("Model mặc định"),
    reasoningEffort: ui("Suy luận"),
    grounded: ui("Chỉ trả lời có căn cứ"),
    instructions: ui("Hướng dẫn"),
  };
}

function FieldValue({
  change,
  nameOf,
}: {
  change: FieldChange;
  nameOf: BuilderCardContext["nameOf"];
}) {
  const ui = useAppTranslation();
  const tools = useToolNames();
  const text = (value: string) => value.trim() || ui("trống");
  switch (change.field) {
    case "instructions": {
      const after = change.after as string;
      const lines = after.split("\n").filter((line) => line.trim()).length;
      if (!after.trim()) return ui("Xóa hết");
      return (change.before as string).trim()
        ? ui("Sửa · {{count}} dòng", { count: lines })
        : ui("Viết mới · {{count}} dòng", { count: lines });
    }
    case "avatarSeed":
      return ui("Mặt mới");
    case "grounded":
      return (
        <Arrow
          before={change.before ? ui("Bật") : ui("Tắt")}
          after={change.after ? ui("Bật") : ui("Tắt")}
        />
      );
    case "reasoningEffort": {
      const level = (value: string) => {
        const known = REASONING_EFFORTS.find((effort) => effort.id === value);
        return known ? ui(known.name) : ui("Theo hệ thống");
      };
      return (
        <Arrow before={level(change.before as string)} after={level(change.after as string)} />
      );
    }
    case "modelConfigurationId": {
      const model = (value: string) =>
        value ? nameOf("modelConfigurationId", value) : ui("Tự động");
      return (
        <Arrow before={model(change.before as string)} after={model(change.after as string)} />
      );
    }
    case "starterPrompts":
      return (
        <ListDiff
          before={change.before as string[]}
          after={change.after as string[]}
          name={(v) => v}
        />
      );
    case "tools":
      return (
        <ListDiff
          before={change.before as string[]}
          after={change.after as string[]}
          name={(tool) =>
            agentTools.includes(tool as AgentTool)
              ? tool === "search"
                ? ui("Tìm trong tài liệu nội bộ")
                : tools[tool as AgentTool].label
              : tool
          }
        />
      );
    case "sourceIds":
    case "documentSetIds":
    case "mcpServerIds": {
      const field = change.field;
      return (
        <ListDiff
          before={change.before as string[]}
          after={change.after as string[]}
          name={(id) => nameOf(field, id)}
        />
      );
    }
    default:
      return <Arrow before={text(change.before as string)} after={text(change.after as string)} />;
  }
}

function Arrow({ before, after }: { before: ReactNode; after: ReactNode }) {
  return (
    <span className="line-clamp-3">
      <span className="text-content-muted">{before}</span> → {after}
    </span>
  );
}

/** What a list gained and lost, one line each. */
function ListDiff({
  before,
  after,
  name,
}: {
  before: string[];
  after: string[];
  name: (value: string) => string;
}) {
  const added = after.filter((value) => !before.includes(value));
  const removed = before.filter((value) => !after.includes(value));
  return (
    <ul className="flex flex-col gap-0.5">
      {added.map((value) => (
        <li key={`+${value}`}>+ {name(value)}</li>
      ))}
      {removed.map((value) => (
        <li key={`-${value}`} className="text-content-muted line-through">
          {name(value)}
        </li>
      ))}
    </ul>
  );
}
