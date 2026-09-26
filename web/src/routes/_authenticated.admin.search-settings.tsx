import { createFileRoute } from "@tanstack/react-router";
import { mayWarmAdminPage } from "@/components/app-shell/admin-prefetch";
import { SearchSettingsPage } from "@/features/search-settings/search-settings-page";
import {
  getSearchSettingsOptions,
  listEmbeddingModelPresetsOptions,
  listEmbeddingProvidersOptions,
} from "@/lib/hey-api/@tanstack/react-query.gen";

export const Route = createFileRoute("/_authenticated/admin/search-settings")({
  loader: ({ context: { queryClient } }) => {
    if (!mayWarmAdminPage(queryClient, "searchSettings")) return;
    void queryClient.prefetchQuery({ ...getSearchSettingsOptions(), retry: false });
    void queryClient.prefetchQuery({ ...listEmbeddingProvidersOptions(), retry: false });
    void queryClient.prefetchQuery({ ...listEmbeddingModelPresetsOptions(), retry: false });
  },
  component: SearchSettingsPage,
});
