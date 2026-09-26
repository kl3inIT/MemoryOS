import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useActionNotifications } from "@/components/ui/action-notifications";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { actionErrorText } from "@/lib/action-errors";
import {
  changeChatLibraryFileMutation,
  emptyChatLibraryTrashMutation,
  purgeChatLibraryFileMutation,
  restoreChatLibraryFileMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { AskExtras } from "./file-ask-composer";
import { uploadChatFile } from "./files";
import {
  deleteLibraryFile,
  invalidateLibrary,
  libraryUpload,
  refusedBy,
  type LibraryFile,
} from "./library";
import type { LibraryChat } from "./library-chat";
import type { RowActions } from "./library-rows";

/** How long one command on one file may take before it counts as failed. */
const commandTimeout = 30_000;
/** Asking about a file may copy it and wait for its extraction first. */
const askTimeout = 120_000;
const pathOf = (file: LibraryFile) => ({ path: { source: file.source, id: file.id } });

/**
 * The library's commands. A command over a selection runs one request per file, because a selection is normally
 * part refused and one refusal must not decide the rest; the refusals stay on the page rather than in a dialog,
 * because a per-file message is runtime text, not a UI key. What simply succeeded is said by a notification.
 */
export function useLibraryActions({
  chat,
  trashDays,
  onListChanged,
}: {
  chat?: LibraryChat;
  /** How long the trash keeps a file here; 0 deletes at once. */
  trashDays: number | undefined;
  /** A change can empty the page being read, so the list restarts where the remaining files are. */
  onListChanged: () => void;
}) {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const notify = useActionNotifications();
  const [refusals, setRefusals] = useState<string[]>([]);
  const invalidate = () => invalidateLibrary(cache);
  const remove = useMutation({
    mutationFn: (file: LibraryFile) => deleteLibraryFile(file, AbortSignal.timeout(commandTimeout)),
  });
  const restore = useMutation(restoreChatLibraryFileMutation());
  const purge = useMutation(purgeChatLibraryFileMutation());
  const emptyTrash = useMutation(emptyChatLibraryTrashMutation());
  const change = useMutation({ ...changeChatLibraryFileMutation(), onSuccess: invalidate });

  /** Runs one command over each file and reports each refusal by the file's name. */
  const eachFile = async (
    targets: readonly LibraryFile[],
    run: (file: LibraryFile) => Promise<unknown>,
    refusal: (file: LibraryFile, failure: unknown) => string,
    done: (count: number) => string,
  ) => {
    const failures: string[] = [];
    let succeeded = 0;
    try {
      for (const file of targets) {
        try {
          await run(file);
          succeeded += 1;
        } catch (failure) {
          failures.push(`${file.filename}: ${refusal(file, failure)}`);
        }
      }
    } finally {
      setRefusals(failures);
      if (succeeded > 0) notify({ title: done(succeeded), tone: "success" });
      onListChanged();
      await invalidate();
    }
  };

  /** One trash command: what it says when it worked, or the failure it names. */
  const act = async (run: () => Promise<string | void>, done: string) => {
    setRefusals([]);
    try {
      const said = await run();
      notify({ title: typeof said === "string" ? said : done, tone: "success" });
    } catch (failure) {
      setRefusals([actionErrorText(failure)]);
    } finally {
      await invalidate();
    }
  };

  const deleteFiles = (targets: readonly LibraryFile[]) =>
    eachFile(
      targets,
      (file) => remove.mutateAsync(file),
      (_file, failure) => {
        const holders = refusedBy(failure);
        return holders.length > 0
          ? ui("Đang dùng trong {{name}}", { name: holders.join(", ") })
          : actionErrorText(failure);
      },
      (count) =>
        trashDays === 0
          ? ui("Đã xoá vĩnh viễn {{count}} tệp.", { count })
          : ui("Đã chuyển {{count}} tệp vào thùng rác.", { count }),
    );

  const restoreFiles = (targets: readonly LibraryFile[]) =>
    eachFile(
      targets,
      (file) =>
        restore.mutateAsync({ ...pathOf(file), signal: AbortSignal.timeout(commandTimeout) }),
      (_file, failure) => actionErrorText(failure),
      (count) => ui("Đã khôi phục {{count}} tệp.", { count }),
    );

  const purgeFiles = (targets: readonly LibraryFile[]) =>
    eachFile(
      targets,
      (file) => purge.mutateAsync({ ...pathOf(file), signal: AbortSignal.timeout(commandTimeout) }),
      (_file, failure) => actionErrorText(failure),
      (count) => ui("Đã xoá vĩnh viễn {{count}} tệp.", { count }),
    );

  const emptyTheTrash = () =>
    act(async () => {
      const { purged } = await emptyTrash.mutateAsync({
        signal: AbortSignal.timeout(commandTimeout),
      });
      return ui("Đã xoá vĩnh viễn {{count}} tệp.", { count: purged });
    }, ui("Đã dọn sạch thùng rác."));

  const rename = (file: LibraryFile, filename: string) =>
    change.mutateAsync({
      ...pathOf(file),
      body: { filename },
      signal: AbortSignal.timeout(commandTimeout),
    });

  /**
   * A question asked where the file is read (MEM-152): the file becomes an upload, a conversation is created for
   * it, and Chat attaches it and sends the question once the conversation is open. An empty question opens that
   * conversation with the files attached and nothing sent. A crop applied in the preview is asked about as
   * itself, so the question is about what was on screen rather than the untouched original.
   */
  const askAbout = async (
    file: LibraryFile | undefined,
    question: string,
    extras: AskExtras & { edited?: File },
  ) => {
    if (!chat) return;
    const signal = AbortSignal.timeout(askTimeout);
    const subject = extras.edited
      ? await uploadChatFile(extras.edited, crypto.randomUUID(), signal, () => {})
      : file && (await libraryUpload(file, signal));
    if (!subject) return;
    const attach = [subject.id];
    for (const chosen of extras.library) attach.push((await libraryUpload(chosen, signal)).id);
    for (const chosen of extras.uploads)
      attach.push((await uploadChatFile(chosen, crypto.randomUUID(), signal, () => {})).id);
    // The copies made for the question are library files too.
    await invalidate();
    await chat.ask({ question, title: question || subject.filename, attach }, signal);
  };

  /** The commands a file row offers, beside the ones that open a dialog of the page. */
  const rowCommands: Pick<
    RowActions,
    "onFavorite" | "onRestore" | "onPurge" | "onRetried" | "onRemoveFromProject"
  > = {
    onFavorite: (file) =>
      change.mutate(
        {
          ...pathOf(file),
          body: { favorite: !file.favorite },
          signal: AbortSignal.timeout(commandTimeout),
        },
        { onError: (failure) => notify({ title: actionErrorText(failure), tone: "error" }) },
      ),
    onRestore: (file) =>
      act(
        () => restore.mutateAsync({ ...pathOf(file), signal: AbortSignal.timeout(commandTimeout) }),
        ui("Đã khôi phục {{name}}.", { name: file.filename }),
      ),
    onPurge: (file) =>
      act(
        () => purge.mutateAsync({ ...pathOf(file), signal: AbortSignal.timeout(commandTimeout) }),
        ui("Đã xoá vĩnh viễn {{name}}.", { name: file.filename }),
      ),
    onRetried: invalidate,
    onRemoveFromProject:
      chat &&
      (async (file, project) => {
        await chat.removeFromProject(project.id, file.id, AbortSignal.timeout(commandTimeout));
        notify({
          title: ui("Đã gỡ khỏi dự án {{name}}.", { name: project.name }),
          tone: "success",
        });
        await invalidate();
      }),
  };

  return {
    refusals,
    dismissRefusals: () => setRefusals([]),
    deleteFiles,
    restoreFiles,
    purgeFiles,
    emptyTheTrash,
    rename,
    askAbout,
    rowCommands,
  };
}

export type LibraryActions = ReturnType<typeof useLibraryActions>;
