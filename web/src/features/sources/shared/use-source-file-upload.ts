import { useMutation } from "@tanstack/react-query";
import { putAuthorizedObject, sha256 } from "@/lib/direct-upload";
import {
  finalizeSourceUploadMutation,
  initiateSourceUploadMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { PendingSourceFinalize } from "@/features/sources/upload/source-upload-recovery-context";

export type SourceUploadPhase = "hashing" | "authorizing" | "uploading";

/**
 * The direct upload of one file into a File Source: its checksum, an upload authorization, the
 * object itself, then finalization. Storing and finalizing are separate steps because a stored
 * object is recoverable: finalization can be retried without uploading the file again.
 */
export function useSourceFileUpload() {
  const initiate = useMutation(initiateSourceUploadMutation());
  const finalize = useMutation(finalizeSourceUploadMutation());

  /** Uploads the file to object storage and returns what finalization needs. */
  async function store(
    sourceId: string,
    file: File,
    signal: AbortSignal,
    {
      onPhase,
      onProgress,
    }: { onPhase: (phase: SourceUploadPhase) => void; onProgress: (percent: number) => void },
  ): Promise<PendingSourceFinalize> {
    onPhase("hashing");
    const checksum = await sha256(file, signal);
    onPhase("authorizing");
    const authorization = await initiate.mutateAsync({
      path: { sourceId },
      body: {
        filename: file.name,
        mediaType: file.type || "application/octet-stream",
        sizeBytes: file.size,
        sha256: checksum,
      },
      signal,
    });
    onProgress(0);
    onPhase("uploading");
    await putAuthorizedObject(authorization, file, signal, onProgress);
    return { sourceId, uploadId: authorization.uploadId, filename: file.name };
  }

  /** Registers a stored object with its Source. */
  async function register(stored: PendingSourceFinalize, signal: AbortSignal) {
    await finalize.mutateAsync({
      path: { sourceId: stored.sourceId, uploadId: stored.uploadId },
      signal,
    });
  }

  return { store, finalize: register };
}
