import { createFileRoute } from "@tanstack/react-router";
import { ModelsPage } from "@/features/models/models-page";
import {
  getChatModelDefaultOptions,
  listChatProviderAdaptersOptions,
  listChatProvidersOptions,
} from "@/lib/hey-api/@tanstack/react-query.gen";

export const Route = createFileRoute("/_authenticated/admin/models")({
  loader: ({ context: { queryClient } }) => {
    void queryClient.prefetchQuery({ ...listChatProvidersOptions(), retry: false });
    void queryClient.prefetchQuery({ ...listChatProviderAdaptersOptions(), retry: false });
    void queryClient.prefetchQuery({ ...getChatModelDefaultOptions(), retry: false });
  },
  component: ModelsPage,
});
