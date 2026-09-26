import { useState } from "react";
import { AppShellHeader } from "@/components/app-shell/app-shell-header";
import { useActionNotifications } from "@/components/ui/action-notifications";
import { PageHeader, SettingsLayout } from "@/components/composites/settings-layout";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { ChatFilePreviewModal } from "./file-preview-modal";
import { libraryPreviewTarget, type LibraryFile } from "./library";
import type { LibraryChat } from "./library-chat";
import { DeleteFilesDialog, PurgeFilesDialog, RenameDialog } from "./library-dialogs";
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
 * The file library: uploads, files run_python generated and generated images in one owner-private list. What it
 * offers of Chat — asking about a file, Projects, conversation retention — comes in through `chat`.
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
    onPreview: setPreview,
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
  };
  const toolbarHandlers: LibraryToolbarHandlers = {
    onSearch: library.setSearch,
    onMode: (next) => library.filterBy({ mode: next }),
    onSources: (next) => library.filterBy({ source: next }),
    onCategories: (next) => library.filterBy({ category: next }),
    onSort: (next) => library.filterBy({ sort: next }),
    onLayout: setLayout,
  };

  return (
    <>
      <AppShellHeader title={ui("Thư viện")} />
      <SettingsLayout wide>
        <LibraryDropZone onFiles={uploads.start}>
          <PageHeader
            title={ui("Thư viện")}
            actions={
              <>
                <LibraryUploadButton onFiles={uploads.start} />
                <LibrarySettingsButton
                  usage={library.usage.data}
                  usageFailed={library.usage.isError}
                  trashDays={trashWindow.data}
                  onCategory={(category) =>
                    library.filterBy({ view: "ready", category: [category] })
                  }
                  retention={chat && <chat.RetentionSection />}
                />
              </>
            }
          />

          <div className="mt-6 flex flex-col gap-6 lg:flex-row lg:gap-8">
            <div className="lg:w-52 lg:shrink-0">
              <LibraryRail
                view={view}
                counts={{ [view]: library.page.data?.totalCount }}
                usage={library.usage.data}
                onView={(next) => {
                  library.filterBy({ view: next });
                  actions.dismissRefusals();
                }}
                onShowLargest={() => library.filterBy({ view: "ready", sort: "LARGEST" })}
              />
            </div>

            <div className="flex min-w-0 flex-1 flex-col gap-4">
              <LibraryToolbar
                sortable={view === "ready" || view === "favorite"}
                state={toolbarState}
                handlers={toolbarHandlers}
              />
              <LibraryFilterPills state={toolbarState} handlers={toolbarHandlers} />

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

              <LibraryNotices
                archive={archive}
                refusals={actions.refusals}
                onDismiss={actions.dismissRefusals}
              />

              {view === "trash" && (
                <TrashBanner days={trashWindow.data} onEmpty={actions.emptyTheTrash} />
              )}

              {library.mode === "content" ? (
                <ContentResults
                  query={library.query}
                  matches={library.matches}
                  onOpen={setPreview}
                  actions={rowActions}
                />
              ) : (
                <LibraryFiles
                  library={library}
                  layout={layout}
                  actions={rowActions}
                  emptyAction={<LibraryUploadButton onFiles={uploads.start} />}
                />
              )}
            </div>
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
