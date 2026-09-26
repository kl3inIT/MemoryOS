import { useMutation, useQueryClient } from "@tanstack/react-query";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { appText } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import {
  cancelSearchFutureGenerationMutation,
  deleteEmbeddingProviderMutation,
  restoreSearchPastGenerationMutation,
  switchSearchFutureGenerationMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { EmbeddingProviderResponse } from "@/lib/hey-api/types.gen";
import { refreshSearchSettings, searchSettingsProblem, type Generation } from "./search-settings";

export type Confirmation =
  | { kind: "switch"; future: Generation }
  | { kind: "cancel"; future: Generation }
  | { kind: "restore"; past: Generation }
  | { kind: "deleteProvider"; provider: EmbeddingProviderResponse };

/** The page's confirmed actions; each one rereads the settings and providers once it succeeds. */
function useSearchSettingsMutations() {
  const client = useQueryClient();
  const onSuccess = () => refreshSearchSettings(client);
  return {
    switchFuture: useMutation({ ...switchSearchFutureGenerationMutation(), onSuccess }),
    cancelFuture: useMutation({ ...cancelSearchFutureGenerationMutation(), onSuccess }),
    restorePast: useMutation({ ...restoreSearchPastGenerationMutation(), onSuccess }),
    deleteProvider: useMutation({ ...deleteEmbeddingProviderMutation(), onSuccess }),
  };
}

export function SearchSettingsConfirm({
  confirmation,
  onClose,
}: {
  confirmation: Confirmation;
  onClose: () => void;
}) {
  const ui = useAppTranslation();
  const mutations = useSearchSettingsMutations();
  const common = {
    open: true,
    onOpenChange: (open: boolean) => {
      if (!open) onClose();
    },
  };
  switch (confirmation.kind) {
    case "switch":
      return (
        <ConfirmDialog
          {...common}
          title={ui(appText("Chuyển sang {{model}}?", { model: confirmation.future.model }))}
          description={ui("Index đang dùng được giữ 7 ngày để hoàn tác.")}
          confirmTone="default"
          confirmLabel={ui("Chuyển index")}
          pendingLabel={ui("Đang chuyển")}
          onConfirm={async () => {
            await mutations.switchFuture.mutateAsync({});
          }}
          errorMessage={(cause) => searchSettingsProblem(cause, "switch")}
        />
      );
    case "cancel":
      return (
        <ConfirmDialog
          {...common}
          title={ui("Hủy dựng lại?")}
          description={ui(
            appText("Index {{model}} đang dựng sẽ bị xoá.", { model: confirmation.future.model }),
          )}
          confirmLabel={ui("Hủy dựng lại")}
          pendingLabel={ui("Đang hủy")}
          onConfirm={async () => {
            await mutations.cancelFuture.mutateAsync({});
          }}
          errorMessage={(cause) => searchSettingsProblem(cause, "cancel")}
        />
      );
    case "restore":
      return (
        <ConfirmDialog
          {...common}
          title={ui(appText("Hoàn tác về {{model}}?", { model: confirmation.past.model }))}
          description={ui("Tìm kiếm dùng lại index này ngay.")}
          confirmTone="default"
          confirmLabel={ui("Hoàn tác")}
          pendingLabel={ui("Đang hoàn tác")}
          onConfirm={async () => {
            await mutations.restorePast.mutateAsync({
              path: { generationId: confirmation.past.id },
            });
          }}
          errorMessage={(cause) => searchSettingsProblem(cause, "restore")}
        />
      );
    case "deleteProvider":
      return (
        <ConfirmDialog
          {...common}
          title={ui(appText("Xoá provider {{name}}?", { name: confirmation.provider.name }))}
          description={ui("Key đã lưu bị xoá cùng provider.")}
          confirmLabel={ui("Xoá provider")}
          pendingLabel={ui("Đang xoá")}
          onConfirm={async () => {
            await mutations.deleteProvider.mutateAsync({
              path: { providerId: confirmation.provider.id },
            });
          }}
          errorMessage={(cause) => searchSettingsProblem(cause, "deleteProvider")}
        />
      );
  }
}
