import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  listOcrConnectionsOptions,
  listOcrConnectionsQueryKey,
  listOcrProvidersOptions,
  saveOcrConnectionMutation,
  selectOcrProviderMutation,
  testOcrConnectionMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { OcrProviderResponse } from "@/lib/hey-api/types.gen";
import { presentProblem, type ErrorMessage } from "@/lib/problem-presentation";

export type OcrProvider = OcrProviderResponse["provider"];

/** Product names are proper nouns. */
export const ocrProviderNames: Record<OcrProvider, string> = {
  PADDLEOCR_VL: "PaddleOCR-VL",
};

/** A failed OCR connection request as the page shows it. */
export function ocrProblem(error: unknown): ErrorMessage {
  return presentProblem(error, "mutation").message;
}

/** The installed OCR providers, the Tenant's connections and the choice of the reader. */
export function useOcrConnections(enabled: boolean) {
  const cache = useQueryClient();
  const providers = useQuery({ ...listOcrProvidersOptions(), enabled, retry: false });
  const connections = useQuery({ ...listOcrConnectionsOptions(), enabled, retry: false });
  const select = useMutation({
    ...selectOcrProviderMutation(),
    onSuccess: () => cache.invalidateQueries({ queryKey: listOcrConnectionsQueryKey() }),
  });
  return { providers, connections, select };
}

/** Saving and testing one provider's connection. */
export function useOcrConnectionMutations() {
  const cache = useQueryClient();
  const save = useMutation({
    ...saveOcrConnectionMutation(),
    onSuccess: () => cache.invalidateQueries({ queryKey: listOcrConnectionsQueryKey() }),
  });
  const test = useMutation(testOcrConnectionMutation());
  return { save, test };
}
