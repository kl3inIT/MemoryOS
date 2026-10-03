import { Fragment, useRef, type ReactNode } from "react";
import { formatUiDay } from "@/i18n/format";
import { Link } from "@tanstack/react-router";
import {
  Bot,
  Building2,
  Clock,
  Download,
  ExternalLink,
  FileText,
  MessageSquare,
  Mic,
  MoreHorizontal,
  Star,
  Users,
} from "lucide-react";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
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
import { StatusBadge } from "@/components/ui/status-badge";
import { useAppTranslation, type AppTranslate } from "@/i18n/use-app-translation";
import { i18n } from "@/i18n/index";
import { fileSize } from "@/lib/file-size";
import { cn } from "@/lib/utils";
import {
  entryDownloadUrl,
  entryReason,
  entryThumbnailUrl,
  meetingMinutes,
  type LibraryEntry,
} from "./library-entries";
import { categoryLabels, entryIcon, entryKindLabels } from "./library-labels";
import type { LibraryEntryView } from "./library-views";
import { LibraryPicture, rowActionsReveal } from "./library-rows";
import type { LibraryLayout } from "./library-toolbar";

/** What a row can do; the page decides which of them it has, and the row offers only what applies to its kind. */
export type EntryActions = {
  /** Opens a file in the file preview, or a Source document in the Search reader. */
  onPreview: (entry: LibraryEntry, trigger: HTMLElement) => void;
  onStar: (entry: LibraryEntry) => void;
  /** A download or a link the row itself follows, so Gần đây learns of it. */
  onOpened: (entry: LibraryEntry) => void;
  /** Absent without Chat, which leaves the command out. */
  onAsk?: (entry: LibraryEntry) => void;
  onMinutes: (entry: LibraryEntry) => void;
  onTranscript: (entry: LibraryEntry) => void;
  /** The row whose command is still running, which keeps it from being started twice. */
  pending?: string;
};

/**
 * Rows of what the person can see or use, laid out as the owned files are: a name, one line of what it is, and
 * why it is here. Nothing is selectable, because nothing a reference offers applies to many rows at once.
 */
export function LibraryEntryList({
  entries,
  view,
  layout,
  actions,
}: {
  entries: readonly LibraryEntry[];
  view: LibraryEntryView;
  layout: LibraryLayout;
  actions: EntryActions;
}) {
  const ui = useAppTranslation();
  if (layout === "grid")
    return (
      <ul aria-label={ui("Mục")} className="grid grid-cols-2 gap-3 sm:grid-cols-3 xl:grid-cols-4">
        {entries.map((entry) => (
          <li key={`${entry.kind}:${entry.id}`}>
            <EntryCard entry={entry} actions={actions} />
          </li>
        ))}
      </ul>
    );
  return (
    <ul aria-label={ui("Mục")} className="flex flex-col gap-2">
      {entries.map((entry) => (
        <li key={`${entry.kind}:${entry.id}`}>
          <EntryRow entry={entry} view={view} actions={actions} />
        </li>
      ))}
    </ul>
  );
}

function EntryRow({
  entry,
  view,
  actions,
}: {
  entry: LibraryEntry;
  view: LibraryEntryView;
  actions: EntryActions;
}) {
  const ui = useAppTranslation();
  const reason = entryReason(entry);
  return (
    // The row's state is drawn around the Item, which owns its own border: a pointer over the row, and an open
    // menu, which takes the pointer off the row but still acts on its entry.
    <div className="rounded-lg transition-colors hover:bg-surface-subtle has-[[data-state=open]]:bg-surface-subtle">
      <Item variant="outline">
        <ItemMedia variant="image">
          <span className="flex size-full items-center justify-center bg-surface-sunken">
            <LibraryPicture
              source={entryThumbnailUrl(entry)}
              fallback={entryIcon(entry)}
              className="size-full object-cover"
            />
          </span>
        </ItemMedia>
        <ItemContent className="min-w-0">
          <EntryName entry={entry} actions={actions} />
          <ItemDescription>
            <span className="flex flex-wrap items-center gap-x-1.5">
              <EntryMeta entry={entry} view={view} />
            </span>
          </ItemDescription>
          {reason && (
            <ItemDescription>
              <span className="block truncate">{ui(reason)}</span>
            </ItemDescription>
          )}
        </ItemContent>
        <ItemActions>
          <div className={rowActionsReveal}>
            <EntryActionButtons entry={entry} actions={actions} />
          </div>
        </ItemActions>
      </Item>
    </div>
  );
}

/**
 * The name opens the entry: a file in its preview, a Source document in the Search reader and a meeting on its own
 * page. A document Search does not serve yet cannot be opened, and says so.
 */
function EntryName({ entry, actions }: { entry: LibraryEntry; actions: EntryActions }) {
  const ui = useAppTranslation();
  const className =
    "flex min-w-0 items-center gap-2 rounded-sm text-left font-main-ui-action outline-none focus-visible:ring-3 focus-visible:ring-focus-ring/40";
  const star = entry.starred && (
    <Star
      role="img"
      className="size-3.5 shrink-0 fill-current text-status-warning-content"
      aria-label={ui("Có gắn sao")}
    />
  );
  if (entry.kind === "MEETING")
    return (
      <Link
        to="/meetings/$meetingId"
        params={{ meetingId: entry.id }}
        className={className}
        onClick={() => actions.onOpened(entry)}
      >
        <span className="truncate">{entry.name}</span>
        {star}
      </Link>
    );
  if (entry.kind === "DOCUMENT" && !entry.document?.generation)
    return (
      <span className={cn(className, "text-content-secondary")}>
        <span className="truncate">{entry.name}</span>
        {star}
        <StatusBadge tone="neutral" size="sm">
          {ui("Đang lập chỉ mục")}
        </StatusBadge>
      </span>
    );
  return (
    <button
      type="button"
      className={className}
      onClick={(event) => actions.onPreview(entry, event.currentTarget)}
    >
      <span className="truncate">{entry.name}</span>
      {star}
    </button>
  );
}

/** What the entry is and what it holds, in one line; a meeting says how far along it is instead of a size. */
function EntryMeta({ entry, view }: { entry: LibraryEntry; view: LibraryEntryView }) {
  const ui = useAppTranslation();
  const parts: ReactNode[] = [entryKindLabels(ui)[entry.kind]];
  if (entry.meeting) {
    parts.push(<MeetingStatus status={entry.meeting.status} />);
    const minutes = meetingMinutes(entry.meeting.durationMs);
    if (minutes > 0) parts.push(ui("{{count}} phút", { count: minutes }));
  } else {
    if (entry.category) parts.push(categoryLabels(ui)[entry.category]);
    if (entry.sizeBytes !== null) parts.push(fileSize(entry.sizeBytes, i18n.language));
  }
  parts.push(
    view === "recent" && entry.openedAt
      ? ui("Đã mở {{date}}", { date: formatUiDay(entry.openedAt) })
      : formatUiDay(entry.at),
  );
  return (
    <>
      {parts.map((part, index) => (
        <Fragment key={index}>
          {index > 0 && <span aria-hidden="true">·</span>}
          <span>{part}</span>
        </Fragment>
      ))}
    </>
  );
}

function MeetingStatus({ status }: { status: NonNullable<LibraryEntry["meeting"]>["status"] }) {
  const ui = useAppTranslation();
  if (status === "RECORDING")
    return (
      <StatusBadge tone="danger" size="sm">
        {ui("Đang ghi")}
      </StatusBadge>
    );
  if (status === "TRANSCRIBING")
    return (
      <StatusBadge tone="info" size="sm">
        {ui("Đang chép lời")}
      </StatusBadge>
    );
  return (
    <StatusBadge tone="neutral" size="sm">
      {ui("Đã kết thúc")}
    </StatusBadge>
  );
}

/** A grid card for the same entry: the picture first, because that is why a grid was chosen. */
function EntryCard({ entry, actions }: { entry: LibraryEntry; actions: EntryActions }) {
  const ui = useAppTranslation();
  const reason = entryReason(entry);
  const picture = (
    <LibraryPicture
      source={entryThumbnailUrl(entry)}
      fallback={entryIcon(entry, "size-7 text-content-muted")}
      className="size-full object-cover"
    />
  );
  const frame =
    "flex aspect-4/3 items-center justify-center overflow-hidden rounded-lg bg-surface-sunken outline-none focus-visible:ring-3 focus-visible:ring-focus-ring/40";
  return (
    <div className="relative flex flex-col gap-2 rounded-xl border border-border-subtle p-2 transition-colors hover:border-border-default has-[[data-state=open]]:border-border-default has-[[data-state=open]]:bg-surface-subtle">
      {entry.kind === "MEETING" ? (
        <Link
          to="/meetings/$meetingId"
          params={{ meetingId: entry.id }}
          className={frame}
          aria-label={ui("Mở cuộc họp {{name}}", { name: entry.name })}
          onClick={() => actions.onOpened(entry)}
        >
          {picture}
        </Link>
      ) : entry.kind === "DOCUMENT" && !entry.document?.generation ? (
        <div className={frame}>{picture}</div>
      ) : (
        <button
          type="button"
          className={frame}
          onClick={(event) => actions.onPreview(entry, event.currentTarget)}
          aria-label={ui("Xem trước {{name}}", { name: entry.name })}
        >
          {picture}
        </button>
      )}
      <div className="flex min-w-0 items-start justify-between gap-1">
        <div className="min-w-0">
          <p className="truncate font-main-ui-action" title={entry.name}>
            {entry.name}
          </p>
          <p className="truncate font-secondary-body text-content-muted">
            {entry.kind === "DOCUMENT" && !entry.document?.generation
              ? ui("Đang lập chỉ mục")
              : reason
                ? ui(reason)
                : entryKindLabels(ui)[entry.kind]}
          </p>
        </div>
        <EntryActionButtons entry={entry} actions={actions} compact />
      </div>
    </div>
  );
}

/**
 * The commands of one entry: its download and its star beside the name, and what only its kind offers in the menu.
 * A reference is never renamed, trashed or added anywhere, so those commands are not offered.
 */
function EntryActionButtons({
  entry,
  actions,
  compact = false,
}: {
  entry: LibraryEntry;
  actions: EntryActions;
  compact?: boolean;
}) {
  const ui = useAppTranslation();
  const byPointer = useRef(false);
  const download = entryDownloadUrl(entry);
  return (
    <div className="flex shrink-0 items-center gap-0.5">
      {!compact && download && (
        <IconButton
          size="sm"
          prominence="internal"
          aria-label={ui("Tải về {{name}}", { name: entry.name })}
          title={ui("Tải về")}
          asChild
        >
          <a href={download} download={entry.name} onClick={() => actions.onOpened(entry)}>
            <Download />
          </a>
        </IconButton>
      )}
      <IconButton
        size="sm"
        prominence="internal"
        aria-pressed={entry.starred}
        aria-label={
          entry.starred
            ? ui("Bỏ gắn sao {{name}}", { name: entry.name })
            : ui("Gắn sao {{name}}", { name: entry.name })
        }
        onClick={() => actions.onStar(entry)}
      >
        <Star className={entry.starred ? "fill-current text-status-warning-content" : undefined} />
      </IconButton>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <IconButton
            size="sm"
            prominence="internal"
            aria-label={ui("Thao tác với {{name}}", { name: entry.name })}
            onPointerDown={() => (byPointer.current = true)}
          >
            <MoreHorizontal />
          </IconButton>
        </DropdownMenuTrigger>
        <DropdownMenuContent
          align="end"
          className="w-auto min-w-48"
          onCloseAutoFocus={(event) => {
            // A menu opened with the pointer keeps the trigger's focus ring off; the keyboard still gets it back.
            if (!byPointer.current) return;
            byPointer.current = false;
            event.preventDefault();
          }}
        >
          <EntryMenuItems entry={entry} actions={actions} download={download} ui={ui} />
        </DropdownMenuContent>
      </DropdownMenu>
    </div>
  );
}

function EntryMenuItems({
  entry,
  actions,
  download,
  ui,
}: {
  entry: LibraryEntry;
  actions: EntryActions;
  download: string | undefined;
  ui: AppTranslate;
}) {
  const busy = actions.pending === `${entry.kind}:${entry.id}`;
  const downloadItem = download && (
    <DropdownMenuItem asChild>
      <a href={download} download={entry.name} onClick={() => actions.onOpened(entry)}>
        <Download />
        {ui("Tải về")}
      </a>
    </DropdownMenuItem>
  );
  const askItem = actions.onAsk && (
    <DropdownMenuItem disabled={busy} onSelect={() => actions.onAsk?.(entry)}>
      <MessageSquare />
      {ui("Hỏi Chat")}
    </DropdownMenuItem>
  );
  if (entry.kind === "MEETING")
    return (
      <>
        <DropdownMenuItem asChild>
          <Link
            to="/meetings/$meetingId"
            params={{ meetingId: entry.id }}
            onClick={() => actions.onOpened(entry)}
          >
            <Mic />
            {ui("Mở cuộc họp")}
          </Link>
        </DropdownMenuItem>
        {entry.meeting?.minutesReady && (
          <DropdownMenuItem disabled={busy} onSelect={() => actions.onMinutes(entry)}>
            <FileText />
            {ui("Tải biên bản (Word)")}
          </DropdownMenuItem>
        )}
        {entry.meeting?.hasTranscript && (
          <DropdownMenuItem disabled={busy} onSelect={() => actions.onTranscript(entry)}>
            <Download />
            {ui("Tải lời thoại (Word)")}
          </DropdownMenuItem>
        )}
        {entry.meeting?.minutesReady && askItem}
      </>
    );
  if (entry.kind === "AGENT_FILE") {
    const agent = entry.agents[0];
    return (
      <>
        {askItem}
        {agent && (
          <DropdownMenuItem asChild>
            <Link to="/agents" search={{ agent: agent.id }}>
              <Bot />
              {ui("Mở trợ lý {{name}}", { name: agent.name })}
            </Link>
          </DropdownMenuItem>
        )}
        {downloadItem}
      </>
    );
  }
  if (entry.kind === "DOCUMENT")
    return (
      <>
        {downloadItem}
        {entry.document?.providerUrl && (
          <DropdownMenuItem asChild>
            <a
              href={entry.document.providerUrl}
              target="_blank"
              rel="noopener noreferrer"
              referrerPolicy="no-referrer"
              onClick={() => actions.onOpened(entry)}
            >
              <ExternalLink />
              {ui("Mở trong nguồn")}
            </a>
          </DropdownMenuItem>
        )}
        {!downloadItem && !entry.document?.providerUrl && (
          <DropdownMenuItem disabled>
            <Building2 />
            {ui("Đang lập chỉ mục")}
          </DropdownMenuItem>
        )}
      </>
    );
  return (
    <>
      {entry.sessionId && (
        <DropdownMenuItem asChild>
          <Link to="/chat/$sessionId" params={{ sessionId: entry.sessionId }}>
            <MessageSquare />
            {ui("Mở hội thoại gốc")}
          </Link>
        </DropdownMenuItem>
      )}
      {downloadItem}
    </>
  );
}

/** Nothing to show, said in the terms of the view the person is on. */
export function LibraryEntryEmpty({ view }: { view: LibraryEntryView }) {
  const ui = useAppTranslation();
  const copy: Record<LibraryEntryView, { icon: ReactNode; title: string; description: string }> = {
    recent: {
      icon: <Clock />,
      title: ui("Chưa mở mục nào gần đây"),
      description: ui("Tệp, cuộc họp và tài liệu bạn mở sẽ hiện ở đây để quay lại nhanh."),
    },
    shared: {
      icon: <Users />,
      title: ui("Chưa có gì được chia sẻ với bạn"),
      description: ui("Cuộc họp người khác chia sẻ và tệp của trợ lý bạn dùng sẽ hiện ở đây."),
    },
    documents: {
      icon: <Building2 />,
      title: ui("Chưa có tài liệu nào"),
      description: ui("Tài liệu trong các nguồn bạn được đọc sẽ hiện ở đây."),
    },
    starred: {
      icon: <Star />,
      title: ui("Chưa gắn sao mục nào"),
      description: ui("Bấm ngôi sao trên tệp, cuộc họp hay tài liệu để giữ nó ở chỗ dễ tìm."),
    },
  };
  return (
    <Empty>
      <EmptyHeader>
        <EmptyMedia variant="icon">{copy[view].icon}</EmptyMedia>
        <EmptyTitle>{copy[view].title}</EmptyTitle>
        <EmptyDescription>{copy[view].description}</EmptyDescription>
      </EmptyHeader>
    </Empty>
  );
}
