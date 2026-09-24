import { getGoogleDriveSelectionRequestOptions } from "@/lib/hey-api/@tanstack/react-query.gen";
import type { SourceOperation } from "@/lib/hey-api/types.gen";
import { useSourceSelectionOperation } from "@/features/sources/shared/source-selection-operation";

export function useGoogleDriveSelectionOperation(scope: string, pending?: SourceOperation | null) {
  return useSourceSelectionOperation({
    provider: "drive",
    scope,
    pending,
    recover: (requestId) => getGoogleDriveSelectionRequestOptions({ path: { requestId } }),
  });
}
