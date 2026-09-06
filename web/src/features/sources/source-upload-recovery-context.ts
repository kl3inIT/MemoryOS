import { createContext, useContext } from "react";

export type PendingSourceFinalize = {
  sourceId: string;
  uploadId: string;
  filename: string;
};

export type SourceUploadRecovery = {
  pendingFinalize: PendingSourceFinalize | null;
  setPendingFinalize: (pending: PendingSourceFinalize | null) => void;
};

export const SourceUploadRecoveryContext = createContext<SourceUploadRecovery | null>(null);

export function useSourceUploadRecovery() {
  const recovery = useContext(SourceUploadRecoveryContext);
  if (!recovery) {
    throw new Error("useSourceUploadRecovery must be used within SourceUploadRecoveryProvider");
  }
  return recovery;
}
