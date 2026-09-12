import type { SourceUploadAuthorization } from "@/lib/hey-api/types.gen";
import { sha256 as incrementalSha256 } from "@noble/hashes/sha2.js";

export class DirectUploadError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = "DirectUploadError";
    this.status = status;
  }
}

export async function sha256(file: File, signal: AbortSignal): Promise<string> {
  const hash = incrementalSha256.create();
  try {
    for (let offset = 0; offset < file.size; offset += 1024 * 1024) {
      signal.throwIfAborted();
      const bytes = await file.slice(offset, offset + 1024 * 1024).arrayBuffer();
      signal.throwIfAborted();
      hash.update(new Uint8Array(bytes));
    }
    signal.throwIfAborted();
    return Array.from(hash.digest(), (byte) => byte.toString(16).padStart(2, "0")).join("");
  } finally {
    hash.destroy();
  }
}

export function putAuthorizedObject(
  authorization: Pick<SourceUploadAuthorization, "method" | "uploadUrl" | "requiredHeaders">,
  file: File,
  signal: AbortSignal,
  onProgress: (percent: number) => void,
): Promise<void> {
  const { promise, resolve, reject } = Promise.withResolvers<void>();
  const request = new XMLHttpRequest();
  const abort = () => request.abort();

  request.open(authorization.method, authorization.uploadUrl);
  for (const [name, value] of Object.entries(authorization.requiredHeaders)) {
    request.setRequestHeader(name, value);
  }

  request.upload.addEventListener("progress", (event) => {
    const total = event.lengthComputable ? event.total : file.size;
    if (total > 0) onProgress(Math.min(100, Math.round((event.loaded / total) * 100)));
  });
  request.addEventListener("load", () => {
    signal.removeEventListener("abort", abort);
    if (request.status >= 200 && request.status < 300) {
      onProgress(100);
      resolve();
      return;
    }
    reject(new DirectUploadError(request.status, "Object storage rejected the upload"));
  });
  request.addEventListener("error", () => {
    signal.removeEventListener("abort", abort);
    reject(new DirectUploadError(0, "Object storage could not be reached"));
  });
  request.addEventListener("abort", () => {
    signal.removeEventListener("abort", abort);
    reject(signal.reason ?? new DOMException("Upload cancelled", "AbortError"));
  });

  if (signal.aborted) {
    reject(signal.reason ?? new DOMException("Upload cancelled", "AbortError"));
  } else {
    signal.addEventListener("abort", abort, { once: true });
    request.send(file);
  }
  return promise;
}
