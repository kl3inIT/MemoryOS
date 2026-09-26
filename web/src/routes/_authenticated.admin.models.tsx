import { createFileRoute } from "@tanstack/react-router";
import { mayWarmAdminPage } from "@/components/app-shell/admin-prefetch";
import { ModelsPage } from "@/features/models/models-page";
import {
  getChatModelDefaultOptions,
  listChatProviderAdaptersOptions,
  listChatProvidersOptions,
} from "@/lib/hey-api/@tanstack/react-query.gen";

export const Route = createFileRoute("/_authenticated/admin/models")({
  loader: ({ context: { queryClient } }) => {
    if (!mayWarmAdminPage(queryClient, "models")) return;
    void queryClient.prefetchQuery({ ...listChatProvidersOptions(), retry: false });
    void queryClient.prefetchQuery({ ...listChatProviderAdaptersOptions(), retry: false });
    void queryClient.prefetchQuery({ ...getChatModelDefaultOptions(), retry: false });
  },
  component: ModelsPage,
});
