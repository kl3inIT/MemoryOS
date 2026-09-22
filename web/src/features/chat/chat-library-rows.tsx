import { Fragment, useRef, useState, type ReactNode } from "react";
import { Link } from "@tanstack/react-router";
import {
  Download,
  FileText,
  FolderPlus,
  MessageSquare,
  MoreHorizontal,
  Pencil,
  RotateCcw,
  Star,
  Trash2,
  Undo2,
} from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import {
  Empty,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
} from "@/components/ui/empty";
import { IconButton } from "@/components/ui/icon-button";
import { Item, ItemActions, ItemContent, ItemDescription, ItemMedia } from "@/components/ui/item";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { i18n } from "@/i18n";
import { cn } from "@/lib/utils";
import { sameOriginMutationHeaders } from "@/lib/api";
import { retryChatFile } from "@/lib/hey-api/sdk.gen";
import { fileSize } from "./chat-code";
import { downloadUrl } from "./chat-file-preview";
import {
  groupByDate,
  libraryPreviewTarget,
  libraryThumbnailUrl,
  removeFromProject,
  usageLabel,
  type LibraryDayGroup,
  type LibraryFile,
} from "./chat-library";
import { categoryIcon, categoryLabels, sourceLabels, statusLabel } from "./chat-library-labels";
import { type LibraryLayout } from "./chat-library-toolbar";
import type { LibraryView } from "./chat-library-rail";

export type RowActions = {
  onPreview: (file: LibraryFile) => void;
  onDelete: (file: LibraryFile) => void;
  onAddToProject: (file: LibraryFile) => void;
  onRename: (file: LibraryFile) => void;
  onFavorite: (file: LibraryFile) => void;
  onRestore: (file: LibraryFile) => Promise<void>;
  onPurge: (file: LibraryFile) => Promise<void>;
  onRetried: () => Promise<unknown>;
  onRemovedFromProject: (name: string) => Promise<void>;
};

/**
 * The files themselves, grouped by the day they arrived when that is the order they are in. A row carries its
 * name, what it is and what it costs on one line each; its actions appear on hover and on keyboard focus, so a
 * long list reads as names rather than as buttons.
 */
export function LibraryList({
  files,
  view,
  layout,
  selected,
  grouped,
  actions,
  onSelect,
}: {
  files: LibraryFile[];
  view: LibraryView;
  layout: LibraryLayout;
  selected: string[];
  /** Day headings only make sense while the newest files come first. */
  grouped: boolean;
  actions: RowActions;
  onSelect: (file: LibraryFile) => void;
}) {
  const ui = useAppTranslation();
  const groups = grouped ? groupByDate(files) : [];
  /**
   * Today and yesterday read as words; any other day reads as its date, with the year only when it is not
   * this one, because a year repeated on every heading says nothing.
   */
  const heading = (group: LibraryDayGroup) => {
    if (group.when === "today") return ui("Hôm nay");
    if (group.when === "yesterday") return ui("Hôm qua");
    const date = new Date(`${group.day}T00:00:00`);
    return date.toLocaleDateString(i18n.language, {
      weekday: "long",
      day: "numeric",
      month: "long",
      year: date.getFullYear() === new Date().getFullYear() ? undefined : "numeric",
    });
  };
  if (!grouped)
    return (
      <div className="flex flex-col gap-6">
        <FileGroup
          files={files}
          view={view}
          layout={layout}
          selected={selected}
          actions={actions}
          onSelect={onSelect}
          label={ui("Tệp")}
        />
      </div>
    );
  return (
    <div className="flex flex-col gap-6">
      {groups.map((group) => (
        <FileGroup
          key={group.day}
          files={group.items}
          view={view}
          layout={layout}
          selected={selected}
          actions={actions}
          onSelect={onSelect}
          label={heading(group)}
          showLabel
        />
      ))}
    </div>
  );
}

/** One day's files, laid out as rows or as cards. */
function FileGroup({
  files,
  view,
  layout,
  selected,
  actions,
  onSelect,
  label,
  showLabel = false,
}: {
  files: readonly LibraryFile[];
  view: LibraryView;
  layout: LibraryLayout;
  selected: string[];
  actions: RowActions;
  onSelect: (file: LibraryFile) => void;
  label: string;
  showLabel?: boolean;
}) {
  return (
    <section aria-label={label}>
      {showLabel && (
        <h2 className="mb-1.5 font-secondary-body text-content-muted first-letter:uppercase">
          {label}
        </h2>
      )}
      {layout === "grid" ? (
        <ul className="grid grid-cols-2 gap-3 sm:grid-cols-3 xl:grid-cols-4">
          {files.map((file) => (
            <li key={file.id}>
              <LibraryCard
                file={file}
                view={view}
                selected={selected.includes(file.id)}
                actions={actions}
                onSelect={onSelect}
              />
            </li>
          ))}
        </ul>
      ) : (
        <ul role="list" className="flex flex-col gap-2">
          {files.map((file) => (
            <li key={file.id}>
              <LibraryRow
                file={file}
                view={view}
                selected={selected.includes(file.id)}
                actions={actions}
                onSelect={onSelect}
              />
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function LibraryRow({
  file,
  view,
  selected,
  actions,
  onSelect,
}: {
  file: LibraryFile;
  view: LibraryView;
  selected: boolean;
  actions: RowActions;
  onSelect: (file: LibraryFile) => void;
}) {
  const ui = useAppTranslation();
  return (
    <Item
      variant="outline"
      className={cn(
        "transition-colors hover:border-border-default hover:bg-surface-subtle",
        // An open menu takes the pointer off the row, so the row keeps saying which file the menu acts on.
        "has-[[data-state=open]]:border-border-default has-[[data-state=open]]:bg-surface-subtle",
        // A chosen row is read at a glance while the eye scans the list, so its edge is the strong one.
        selected && "border-border-strong bg-surface-subtle ring-1 ring-border-strong",
      )}
    >
      <Checkbox
        aria-label={ui("Chọn {{name}}", { name: file.filename })}
        checked={selected}
        onCheckedChange={() => onSelect(file)}
      />
      {/* One box whatever the file is: a picture fills it, anything else centres its icon in it, so the
          names below each other start at the same place. */}
      <ItemMedia variant="image" className="bg-surface-sunken">
        <LibraryThumbnail file={file} className="size-full object-cover" />
      </ItemMedia>
      <ItemContent className="min-w-0">
        <button
          type="button"
          className="flex min-w-0 items-center gap-2 rounded-sm text-left font-main-ui-action outline-none focus-visible:ring-3 focus-visible:ring-focus-ring/40"
          onClick={() => actions.onPreview(file)}
        >
          <span className="truncate">{file.filename}</span>
          {file.favorite && (
            <Star
              role="img"
              className="size-3.5 shrink-0 fill-current text-status-warning-content"
              aria-label={ui("Yêu thích")}
            />
          )}
        </button>
        <ItemDescription className="flex flex-wrap items-center gap-x-1.5">
          <RowMeta file={file} view={view} />
        </ItemDescription>
        <FileUsage file={file} onRemoved={actions.onRemovedFromProject} />
      </ItemContent>
      <ItemActions className="opacity-100 transition-opacity md:opacity-0 md:group-hover/item:opacity-100 md:group-focus-within/item:opacity-100 md:has-[[data-state=open]]:opacity-100">
        <RowActionButtons file={file} view={view} actions={actions} />
      </ItemActions>
    </Item>
  );
}

/**
 * The picture of a file: an image shows itself, whether it was generated or uploaded, and anything else shows
 * what it is. A thumbnail that cannot be fetched — still being written, or gone from storage — falls back to
 * the same icon instead of the browser's broken-image mark, which says nothing about the file.
 */
function LibraryThumbnail({
  file,
  className,
  icon,
}: {
  file: LibraryFile;
  className?: string;
  icon?: string;
}) {
  const [failed, setFailed] = useState(false);
  const source = libraryThumbnailUrl(file);
  if (!source || failed) return categoryIcon(file, cn(icon ?? "size-4", "text-content-muted"));
  return (
    <img
      src={source}
      alt=""
      loading="lazy"
      decoding="async"
      className={className}
      onError={() => setFailed(true)}
    />
  );
}

/** One line of facts about a file, in the order the current view makes useful. */
function RowMeta({ file, view }: { file: LibraryFile; view: LibraryView }) {
  const ui = useAppTranslation();
  const sources = sourceLabels(ui);
  const categories = categoryLabels(ui);
  const parts: string[] = [];
  if (view === "pending") parts.push(statusLabel(file, ui));
  else if (view === "trash" && file.deletedAt)
    parts.push(
      ui("Đã xoá {{date}}", { date: new Date(file.deletedAt).toLocaleDateString(i18n.language) }),
    );
  else parts.push(sources[file.source]);
  parts.push(categories[file.category]);
  parts.push(fileSize(file.sizeBytes, i18n.language));
  if (view === "trash" && file.purgeAfter)
    parts.push(
      ui("Xoá vĩnh viễn {{date}}", {
        date: new Date(file.purgeAfter).toLocaleDateString(i18n.language),
      }),
    );
  else parts.push(new Date(file.createdAt).toLocaleDateString(i18n.language));
  return (
    <>
      {parts.map((part, index) => (
        <Fragment key={`${part}-${index}`}>
          {index > 0 && <span aria-hidden="true">·</span>}
          <span>{part}</span>
        </Fragment>
      ))}
    </>
  );
}

/** A grid card for the same file: the picture first, because that is why a grid was chosen. */
function LibraryCard({
  file,
  view,
  selected,
  actions,
  onSelect,
}: {
  file: LibraryFile;
  view: LibraryView;
  selected: boolean;
  actions: RowActions;
  onSelect: (file: LibraryFile) => void;
}) {
  const ui = useAppTranslation();
  return (
    <div
      className={cn(
        "group/card relative flex flex-col gap-2 rounded-xl border border-border-subtle p-2 transition-colors hover:border-border-default",
        // An open menu takes the pointer off the card, so the card keeps saying which file the menu acts on.
        "has-[[data-state=open]]:border-border-default has-[[data-state=open]]:bg-surface-subtle",
        selected && "border-border-strong bg-surface-subtle ring-1 ring-border-strong",
      )}
    >
      {/*
       * The same checkbox as every list on the page wears; a card adds no chrome of its own around it. It
       * stays out of the picture until the pointer reaches the card or the keyboard lands on it — a chosen
       * card is already read by its edge. Clicking leaves focus behind, so the keyboard rule is
       * focus-visible rather than focus-within, and a touch screen, which has no hover, keeps it on screen.
       */}
      <div className="absolute top-4 left-4 z-10 opacity-100 transition-opacity md:opacity-0 md:group-hover/card:opacity-100 md:group-has-[:focus-visible]/card:opacity-100 md:group-has-[[data-state=open]]/card:opacity-100">
        <Checkbox
          aria-label={ui("Chọn {{name}}", { name: file.filename })}
          checked={selected}
          onCheckedChange={() => onSelect(file)}
        />
      </div>
      <button
        type="button"
        className="flex aspect-4/3 items-center justify-center overflow-hidden rounded-lg bg-surface-sunken outline-none focus-visible:ring-3 focus-visible:ring-focus-ring/40"
        onClick={() => actions.onPreview(file)}
        aria-label={ui("Xem trước {{name}}", { name: file.filename })}
      >
        <LibraryThumbnail file={file} className="size-full object-cover" icon="size-7" />
      </button>
      <div className="flex min-w-0 items-start justify-between gap-1">
        <div className="min-w-0">
          {/* No favourite badge here: the card keeps its star button on screen, so a badge would say it twice. */}
          <p className="truncate font-main-ui-action" title={file.filename}>
            {file.filename}
          </p>
          <p className="font-secondary-body text-content-muted">
            {fileSize(file.sizeBytes, i18n.language)}
          </p>
        </div>
        <RowActionButtons file={file} view={view} actions={actions} compact />
      </div>
    </div>
  );
}

/** The actions a file offers, which depend on whether it is usable, still arriving, or in the trash. */
function RowActionButtons({
  file,
  view,
  actions,
  compact = false,
}: {
  file: LibraryFile;
  view: LibraryView;
  actions: RowActions;
  compact?: boolean;
}) {
  if (view === "trash")
    return (
      <TrashActions
        file={file}
        compact={compact}
        onRestore={() => actions.onRestore(file)}
        onPurge={() => actions.onPurge(file)}
      />
    );
  if (view === "pending")
    return (
      <PendingActions
        file={file}
        compact={compact}
        onRetried={actions.onRetried}
        onRemove={() => actions.onDelete(file)}
      />
    );
  return <FileActions file={file} compact={compact} actions={actions} />;
}

export function FileActions({
  file,
  actions,
  compact = false,
}: {
  file: LibraryFile;
  actions: Pick<RowActions, "onDelete" | "onAddToProject" | "onRename" | "onFavorite">;
  compact?: boolean;
}) {
  const ui = useAppTranslation();
  const byPointer = useRef(false);
  return (
    <div className="flex shrink-0 items-center gap-0.5">
      {!compact && (
        <IconButton
          size="sm"
          prominence="internal"
          aria-label={ui("Tải về {{name}}", { name: file.filename })}
          title={ui("Tải về")}
          asChild
        >
          <a href={downloadUrl(libraryPreviewTarget(file))} download={file.filename}>
            <Download />
          </a>
        </IconButton>
      )}
      <IconButton
        size="sm"
        prominence="internal"
        aria-pressed={file.favorite}
        aria-label={
          file.favorite
            ? ui("Bỏ yêu thích {{name}}", { name: file.filename })
            : ui("Đánh dấu yêu thích {{name}}", { name: file.filename })
        }
        onClick={() => actions.onFavorite(file)}
      >
        <Star className={file.favorite ? "fill-current text-status-warning-content" : undefined} />
      </IconButton>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <IconButton
            size="sm"
            prominence="internal"
            aria-label={ui("Thao tác với {{name}}", { name: file.filename })}
            onPointerDown={() => (byPointer.current = true)}
          >
            <MoreHorizontal />
          </IconButton>
        </DropdownMenuTrigger>
        <DropdownMenuContent
          align="end"
          className="w-auto min-w-48"
          onCloseAutoFocus={(event) => {
            // Closing hands focus back to the trigger, which the browser then rings as if the keyboard had
            // reached it. A menu opened with the pointer keeps that ring off; the keyboard still gets it back.
            if (!byPointer.current) return;
            byPointer.current = false;
            event.preventDefault();
          }}
        >
          <DropdownMenuItem onSelect={() => actions.onRename(file)}>
            <Pencil />
            {ui("Đổi tên")}
          </DropdownMenuItem>
          <DropdownMenuItem onSelect={() => actions.onAddToProject(file)}>
            <FolderPlus />
            {ui("Thêm vào dự án")}
          </DropdownMenuItem>
          {file.sessionId && (
            <DropdownMenuItem asChild>
              <Link to="/chat/$sessionId" params={{ sessionId: file.sessionId }}>
                <MessageSquare />
                {ui("Mở hội thoại gốc")}
              </Link>
            </DropdownMenuItem>
          )}
          <DropdownMenuItem asChild>
            <a href={downloadUrl(libraryPreviewTarget(file))} download={file.filename}>
              <Download />
              {ui("Tải về")}
            </a>
          </DropdownMenuItem>
          <DropdownMenuSeparator />
          <DropdownMenuItem
            variant="destructive"
            disabled={!file.deletable}
            onSelect={() => actions.onDelete(file)}
          >
            <Trash2 />
            {file.deletable
              ? ui("Xoá")
              : ui("Đang dùng trong {{name}}", { name: usageLabel(file) })}
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </div>
  );
}

/** A trashed file can only come back or go for good. */
function TrashActions({
  file,
  onRestore,
  onPurge,
  compact,
}: {
  file: LibraryFile;
  onRestore: () => Promise<void>;
  onPurge: () => Promise<void>;
  compact?: boolean;
}) {
  const ui = useAppTranslation();
  return (
    <div className="flex shrink-0 items-center gap-0.5">
      {compact ? (
        <IconButton
          size="sm"
          prominence="internal"
          aria-label={ui("Khôi phục {{name}}", { name: file.filename })}
          onClick={() => void onRestore()}
        >
          <Undo2 />
        </IconButton>
      ) : (
        <Button size="sm" prominence="internal" onClick={() => void onRestore()}>
          <Undo2 className="size-4" aria-hidden="true" />
          {ui("Khôi phục")}
        </Button>
      )}
      <ConfirmDialog
        trigger={
          <IconButton
            size="sm"
            prominence="internal"
            aria-label={ui("Xoá vĩnh viễn {{name}}", { name: file.filename })}
          >
            <Trash2 />
          </IconButton>
        }
        title={ui("Xoá vĩnh viễn?")}
        description={ui(
          "Tệp và nội dung của nó sẽ bị xoá khỏi kho lưu trữ và không thể khôi phục.",
        )}
        confirmLabel={ui("Xoá vĩnh viễn")}
        pendingLabel={ui("Đang xoá…")}
        confirmTone="danger"
        onConfirm={onPurge}
      />
    </div>
  );
}

/** An upload that is not usable yet can only be retried or removed; it has nothing to preview or attach. */
function PendingActions({
  file,
  onRetried,
  onRemove,
  compact,
}: {
  file: LibraryFile;
  onRetried: () => Promise<unknown>;
  onRemove: () => void;
  compact?: boolean;
}) {
  const ui = useAppTranslation();
  const [busy, setBusy] = useState(false);
  const retry = async () => {
    setBusy(true);
    try {
      await retryChatFile({
        path: { fileId: file.id },
        headers: sameOriginMutationHeaders,
        signal: AbortSignal.timeout(30000),
        throwOnError: true,
      });
      await onRetried();
    } finally {
      setBusy(false);
    }
  };
  return (
    <div className="flex shrink-0 items-center gap-0.5">
      {file.status === "FAILED" &&
        file.errorCode !== "UPLOAD_EXPIRED" &&
        (compact ? (
          <IconButton
            size="sm"
            prominence="internal"
            pending={busy}
            aria-label={ui("Thử lại {{name}}", { name: file.filename })}
            onClick={() => void retry()}
          >
            <RotateCcw />
          </IconButton>
        ) : (
          <Button size="sm" prominence="internal" pending={busy} onClick={() => void retry()}>
            <RotateCcw className="size-4" aria-hidden="true" />
            {ui("Thử lại")}
          </Button>
        ))}
      <IconButton
        size="sm"
        prominence="internal"
        aria-label={ui("Gỡ bỏ {{name}}", { name: file.filename })}
        title={ui("Gỡ bỏ")}
        onClick={onRemove}
      >
        <Trash2 />
      </IconButton>
    </div>
  );
}

/** What holds an upload, with a way to take it out of a Project; an assistant's files are edited on the assistant. */
export function FileUsage({
  file,
  onRemoved,
}: {
  file: LibraryFile;
  onRemoved: (name: string) => Promise<void>;
}) {
  const ui = useAppTranslation();
  const [pending, setPending] = useState<string>();
  const [failed, setFailed] = useState(false);
  if (file.usedBy.length === 0) return null;
  return (
    <span className="mt-0.5 flex flex-wrap items-center gap-x-2 font-secondary-body text-content-muted">
      <Badge variant="outline">{ui("Đang dùng trong {{name}}", { name: usageLabel(file) })}</Badge>
      {file.usedBy
        .filter((usage) => usage.kind === "PROJECT")
        .map((usage) => (
          <button
            key={usage.id}
            type="button"
            disabled={pending !== undefined}
            className="underline hover:text-content-primary disabled:opacity-50"
            onClick={async () => {
              setPending(usage.id);
              setFailed(false);
              try {
                await removeFromProject(usage.id, file.id, AbortSignal.timeout(30000));
                await onRemoved(usage.name);
              } catch {
                setFailed(true);
              } finally {
                setPending(undefined);
              }
            }}
          >
            {ui("Gỡ khỏi {{name}}", { name: usage.name })}
          </button>
        ))}
      {failed && (
        <span role="alert" className="text-content-danger">
          {ui("Không gỡ được. Hãy thử lại.")}
        </span>
      )}
    </span>
  );
}

/** Nothing to show, said in the terms of the view the owner is on, with the way out of it. */
export function LibraryEmpty({
  view,
  filtered,
  action,
  onClearFilters,
}: {
  view: LibraryView;
  filtered: boolean;
  /** The upload control itself, which owns the browser's file dialog. */
  action?: ReactNode;
  onClearFilters: () => void;
}) {
  const ui = useAppTranslation();
  if (filtered)
    return (
      <Empty>
        <EmptyHeader>
          <EmptyMedia variant="icon">
            <FileText />
          </EmptyMedia>
          <EmptyTitle>{ui("Không có tệp nào khớp")}</EmptyTitle>
          <EmptyDescription>
            {ui("Hãy bỏ một vài bộ lọc hoặc đổi từ khoá tìm kiếm.")}
          </EmptyDescription>
        </EmptyHeader>
        <Button size="sm" prominence="secondary" onClick={onClearFilters}>
          {ui("Xoá bộ lọc")}
        </Button>
      </Empty>
    );
  const copy: Record<LibraryView, { title: string; description: string }> = {
    ready: {
      title: ui("Thư viện đang trống"),
      description: ui("Tải tệp lên hoặc để Chat tạo ra, tệp sẽ xuất hiện ở đây."),
    },
    favorite: {
      title: ui("Chưa có tệp yêu thích"),
      description: ui("Bấm ngôi sao trên một tệp để giữ nó ở chỗ dễ tìm."),
    },
    pending: {
      title: ui("Không có tệp nào đang xử lý"),
      description: ui("Tệp mới tải lên sẽ hiện ở đây cho tới khi dùng được."),
    },
    trash: {
      title: ui("Thùng rác trống"),
      description: ui("Tệp bạn xoá sẽ nằm ở đây trước khi bị xoá vĩnh viễn."),
    },
  };
  return (
    <Empty>
      <EmptyHeader>
        <EmptyMedia variant="icon">
          {view === "trash" ? <Trash2 /> : view === "favorite" ? <Star /> : <FileText />}
        </EmptyMedia>
        <EmptyTitle>{copy[view].title}</EmptyTitle>
        <EmptyDescription>{copy[view].description}</EmptyDescription>
      </EmptyHeader>
      {view === "ready" && action}
    </Empty>
  );
}
