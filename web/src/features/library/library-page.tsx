import { useState, type ReactNode } from "react";
import { Link } from "@tanstack/react-router";
import { FolderOpen } from "lucide-react";
import { AppShellHeader } from "@/components/app-shell/app-shell-header";
import { useActionNotifications } from "@/components/ui/action-notifications";
import { PageHeader, SettingsLayout } from "@/components/composites/settings-layout";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { ChatFilePreviewModal } from "./file-preview-modal";
import { libraryPreviewTarget, type LibraryFile } from "./library";
import type { LibraryChat } from "./library-chat";
import { DeleteFilesDialog, PurgeFilesDialog, RenameDialog } from "./library-dialogs";
import { recordEntryOpened } from "./library-entries";
import { LibraryEntryViews } from "./library-entry-views";
import { LibraryNotices, TrashBanner } from "./library-notices";
import { ContentResults, LibraryFiles } from "./library-results";
import type { RowActions } from "./library-rows";
import { viewFilterDefaults, type LibrarySearch } from "./library-search";
import { LibrarySettingsButton } from "./library-settings";
import type { LibrarySources } from "./library-sources";
import { LibraryTabs } from "./library-tabs";
import {
  LibraryFilterPills,
  LibrarySelectionBar,
  LibraryToolbar,
  type LibraryLayout,
  type LibraryToolbarHandlers,
} from "./library-toolbar";
import { LibraryDropZone, LibraryUploadButton, LibraryUploadTray } from "./library-uploads";
import { isEntryView } from "./library-views";
import { useLibraryActions } from "./use-library-actions";
import { useLibraryArchive } from "./use-library-archive";
import { useLibraryUploads } from "./use-library-uploads";
import { useLibraryView } from "./use-library-view";

/**
 * The library: everything the person can see or use, on one page. Their own files — uploads, files run_python
 * generated and generated images — keep every command; what reaches them through a share, an assistant or a
 * Source is listed read-only in views of its own. What it offers of Chat — asking about a file, Projects,
 * conversation retention — comes in through `chat`, and the Sources behind the organisation's documents through
 * `sources`.
 */
export function LibraryPage({
  chat,
  sources: Sources,
}: {
  chat?: LibraryChat;
  sources?: LibrarySources;
}) {
  const ui = useAppTranslation();
  const notify = useActionNotifications();
  const library = useLibraryView();
  const { files, selected, setSelected, trashWindow } = library;
  // Without the Sources view the organisation's section holds its documents alone.
  const view = library.view === "sources" && !Sources ? "documents" : library.view;
  const actions = useLibraryActions({
    chat,
    trashDays: trashWindow.data,
    onListChanged: library.showFirstPage,
  });
  const uploads = useLibraryUploads();
  const archive = useLibraryArchive();
  const [layout, setLayout] = useState<LibraryLayout>("list");
  const [preview, setPreview] = useState<LibraryFile>();
  const [renaming, setRenaming] = useState<LibraryFile>();
  const [deleting, setDeleting] = useState<LibraryFile[]>();
  const [purging, setPurging] = useState<LibraryFile[]>();
  const [projectFiles, setProjectFiles] = useState<LibraryFile[]>();
  const chosen = files.filter((file) => selected.includes(file.id));

  const rowActions: RowActions = {
    ...actions.rowCommands,
    onPreview: (file) => {
      // Gần đây lists what the person opened, their own files included.
      recordEntryOpened(file.source, file.id);
      setPreview(file);
    },
    onDelete: (file) => setDeleting([file]),
    onAddToProject: chat && ((file) => setProjectFiles([file])),
    onRename: setRenaming,
  };
  const toolbarState = {
    search: library.search,
    mode: library.mode,
    sources: library.sources,
    categories: library.categories,
    sort: library.sort,
    layout,
    starredOnly: library.starred,
  };
  const toolbarHandlers: LibraryToolbarHandlers = {
    onSearch: library.setSearch,
    onMode: (next) => library.filterBy({ mode: next }),
    onSources: (next) => library.filterBy({ source: next }),
    onCategories: (next) => library.filterBy({ category: next }),
    onSort: (next) => library.filterBy({ sort: next }),
    onLayout: setLayout,
    onStarredOnly: (next) => library.filterBy({ starred: next }),
  };
  // A view with nothing in it and nothing narrowing it has nothing to search, filter or lay out.
  const nothingToArrange =
    library.page.isSuccess &&
    files.length === 0 &&
    !library.filtered &&
    library.search === "" &&
    library.mode === "name";
  const notices = (
    <LibraryNotices
      archive={archive}
      refusals={actions.refusals}
      onDismiss={actions.dismissRefusals}
    />
  );

  return (
    <>
      <AppShellHeader title={ui("Thư viện")} pageHeader />
      <SettingsLayout wide>
        <LibraryDropZone onFiles={uploads.start}>
          <PageHeader
            icon={<FolderOpen />}
            title={ui("Thư viện")}
            description={
              // A phone gives the line to the list; a screen reader still hears it.
              <span className="max-sm:sr-only">
                {ui("Mọi tệp, cuộc họp và tài liệu bạn xem và dùng được, ở cùng một chỗ.")}
              </span>
            }
            actions={
              <>
                <LibraryUploadButton onFiles={uploads.start} />
                <LibrarySettingsButton
                  usage={library.usage.data}
                  usageFailed={library.usage.isError}
                  trashDays={trashWindow.data}
                  onCategory={(category) => library.showView("ready", { category: [category] })}
                  retention={chat && <chat.RetentionSection />}
                />
              </>
            }
          />

          <div className="mt-6">
            <LibraryTabs
              view={view}
              count={library.owned ? library.page.data?.totalCount : undefined}
              usage={library.usage.data}
              sources={Sources !== undefined}
              onView={(next) => {
                library.showView(next);
                actions.dismissRefusals();
              }}
              onShowLargest={() => library.showView("ready", { sort: "LARGEST" })}
            >
              {view === "sources" ? (
                Sources && (
                  <div className="flex min-w-0 flex-col gap-4">
                    {notices}
                    <Sources
                      search={library.search}
                      onSearch={library.setSearch}
                      filters={{
                        status: library.sourceStatus ?? "",
                        provider: library.provider ?? "",
                        access: library.access ?? "",
                      }}
                      onFilters={(next) =>
                        library.filterBy({
                          sourceStatus: (next.status || undefined) as LibrarySearch["sourceStatus"],
                          provider: (next.provider || undefined) as LibrarySearch["provider"],
                          access: (next.access || undefined) as LibrarySearch["access"],
                        })
                      }
                      DocumentsLink={SourceDocumentsLink}
                    />
                  </div>
                )
              ) : isEntryView(view) ? (
                <div className="flex min-w-0 flex-col gap-4">
                  {notices}
                  <LibraryEntryViews
                    view={view}
                    library={library}
                    layout={layout}
                    onLayout={setLayout}
                    chat={chat}
                    onSaveImage={(file) => uploads.start([file])}
                  />
                </div>
              ) : (
                <div className="flex min-w-0 flex-col gap-4">
                  {!nothingToArrange && (
                    <LibraryToolbar
                      sortable={view === "ready"}
                      starrable={view === "ready"}
                      state={toolbarState}
                      handlers={toolbarHandlers}
                    />
                  )}
                  <LibraryFilterPills
                    starrable={view === "ready"}
                    state={toolbarState}
                    handlers={toolbarHandlers}
                  />

                  {/* Always rendered: an empty selection is the bar leaving, which it animates itself. */}
                  <LibrarySelectionBar
                    count={selected.length}
                    packing={archive.state.phase === "packing"}
                    trash={view === "trash"}
                    onDownload={() => void archive.start(chosen)}
                    onAddToProject={chat && (() => setProjectFiles(chosen))}
                    onDelete={() => setDeleting(chosen)}
                    onRestore={() => void actions.restoreFiles(chosen)}
                    onPurge={() => setPurging(chosen)}
                    onClear={() => setSelected([])}
                  />

                  {notices}

                  {view === "trash" && (
                    <TrashBanner days={trashWindow.data} onEmpty={actions.emptyTheTrash} />
                  )}

                  {library.mode === "content" ? (
                    <ContentResults
                      query={library.query}
                      matches={library.matches}
                      onOpen={rowActions.onPreview}
                      actions={rowActions}
                    />
                  ) : (
                    <LibraryFiles
                      library={library}
                      view={view}
                      layout={layout}
                      actions={rowActions}
                      emptyAction={<LibraryUploadButton onFiles={uploads.start} />}
                    />
                  )}
                </div>
              )}
            </LibraryTabs>
          </div>
        </LibraryDropZone>
      </SettingsLayout>

      {preview && (
        <ChatFilePreviewModal
          target={libraryPreviewTarget(preview)}
          siblings={files.map(libraryPreviewTarget)}
          onClose={() => setPreview(undefined)}
          onSaveImage={(file) => uploads.start([file])}
          ask={
            chat && {
              onAsk: (target, question, extras) =>
                actions.askAbout(
                  files.find((item) => item.id === target.id) ??
                    (preview.id === target.id ? preview : undefined),
                  question,
                  extras,
                ),
              ModelPicker: chat.ModelPicker,
            }
          }
        />
      )}
      <LibraryUploadTray
        uploads={uploads.uploads}
        onCancel={uploads.cancel}
        onDismiss={uploads.dismiss}
      />
      {renaming && (
        <RenameDialog
          file={renaming}
          onOpenChange={(open) => !open && setRenaming(undefined)}
          onRename={(filename) => actions.rename(renaming, filename)}
        />
      )}
      {chat && (
        <chat.AddToProjectDialog
          files={projectFiles}
          onOpenChange={(open) => !open && setProjectFiles(undefined)}
          onAdded={(name) => {
            setSelected([]);
            notify({ title: ui("Đã thêm vào dự án {{name}}.", { name }), tone: "success" });
          }}
        />
      )}
      <DeleteFilesDialog
        files={deleting}
        trashDays={trashWindow.data}
        onOpenChange={(open) => !open && setDeleting(undefined)}
        onConfirm={() => actions.deleteFiles(deleting ?? [])}
      />
      <PurgeFilesDialog
        files={purging}
        onOpenChange={(open) => !open && setPurging(undefined)}
        onConfirm={async () => {
          await actions.purgeFiles(purging ?? []);
          setPurging(undefined);
        }}
      />
    </>
  );
}

/**
 * One Source's documents, from the Sources view: the organisation's documents narrowed to it. The Sources view's
 * search and filters mean nothing there, so they are not carried; the page size follows the person.
 */
function SourceDocumentsLink({
  sourceId,
  className,
  children,
}: {
  sourceId: string;
  className?: string;
  children: ReactNode;
}) {
  return (
    <Link
      to="/library"
      search={(current) => ({
        ...current,
        ...viewFilterDefaults,
        view: "documents",
        sourceId,
        q: "",
        page: 0,
      })}
      className={className}
    >
      {children}
    </Link>
  );
}
