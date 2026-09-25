import { useMemo, useState, type ReactNode } from "react";
import {
  SourceUploadRecoveryContext,
  type PendingSourceFinalize,
} from "./source-upload-recovery-context";

export function SourceUploadRecoveryProvider({ children }: { children: ReactNode }) {
  const [pendingFinalize, setPendingFinalize] = useState<PendingSourceFinalize | null>(null);
  const value = useMemo(() => ({ pendingFinalize, setPendingFinalize }), [pendingFinalize]);
  return (
    <SourceUploadRecoveryContext.Provider value={value}>
      {children}
    </SourceUploadRecoveryContext.Provider>
  );
}
