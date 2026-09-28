import { useState } from "react";
import { FolderOpen } from "lucide-react";
import { AppShellHeader } from "@/components/app-shell/app-shell-header";
import { useActionNotifications } from "@/components/ui/action-notifications";
import { PageHeader, SettingsLayout } from "@/components/composites/settings-layout";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { ChatFilePreviewModal } from "./file-preview-modal";
import { libraryPreviewTarget, type LibraryFile } from "./library";
import type { LibraryChat } from "./library-chat";
import { DeleteFilesDialog, PurgeFilesDialog, RenameDialog } from "./library-dialogs";
import { isEntryView, recordEntryOpened } from "./library-entries";
import { LibraryEntryViews } from "./library-entry-views";
import { LibraryNotices, TrashBanner } from "./library-notices";
import { LibraryRail } from "./library-rail";
import { ContentResults, LibraryFiles } from "./library-results";
import type { RowActions } from "./library-rows";
import { LibrarySettingsButton } from "./library-settings";
import {
  LibraryFilterPills,
  LibrarySelectionBar,
  LibraryToolbar,
  type LibraryLayout,
  type LibraryToolbarHandlers,
} from "./library-toolbar";
import { LibraryDropZone, LibraryUploadButton, LibraryUploadTray } from "./library-uploads";
import { useLibraryActions } from "./use-library-actions";
import { useLibraryArchive } from "./use-library-archive";
import { useLibraryUploads } from "./use-library-uploads";
import { useLibraryView } from "./use-library-view";

/**
 * The library: everything the person can see or use, on one page. Their own files — uploads, files run_python
 * generated and generated images — keep every command; what reaches them through a share, an assistant or a
 * Source is listed read-only in views of its own. What it offers of Chat — asking about a file, Projects,
 * conversation retention — comes in through `chat`.
 */
export function LibraryPage({ chat }: { chat?: LibraryChat }) {
  const ui = useAppTranslation();
  const notify = useActionNotifications();
  const library = useLibraryView();
  const { view, files, selected, setSelected, trashWindow } = library;
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
  const notices = (
    <LibraryNotices
      archive={archive}
      refusals={actions.refusals}
      onDismiss={actions.dismissRefusals}
    />
  );

  return (
    <>
      <AppShellHeader title={ui("Thư viện")} />
      <SettingsLayout wide>
        <LibraryDropZone onFiles={uploads.start}>
          <PageHeader
            icon={<FolderOpen />}
            title={ui("Thư viện")}
            description={ui("Mọi tệp, cuộc họp và tài liệu bạn xem và dùng được, ở cùng một chỗ.")}
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

          <div className="mt-6 flex flex-col gap-6 lg:flex-row lg:gap-8">
            <div className="lg:w-52 lg:shrink-0">
              <LibraryRail
                view={view}
                counts={library.owned ? { [view]: library.page.data?.totalCount } : {}}
                usage={library.usage.data}
                onView={(next) => {
                  library.showView(next);
                  actions.dismissRefusals();
                }}
                onShowLargest={() => library.showView("ready", { sort: "LARGEST" })}
              />
            </div>

            {isEntryView(view) ? (
              <div className="flex min-w-0 flex-1 flex-col gap-4">
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
              <div className="flex min-w-0 flex-1 flex-col gap-4">
                <LibraryToolbar
                  sortable={view === "ready"}
                  starrable={view === "ready"}
                  state={toolbarState}
                  handlers={toolbarHandlers}
                />
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
