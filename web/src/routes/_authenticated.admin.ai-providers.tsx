import { createFileRoute, stripSearchParams } from "@tanstack/react-router";
import { mayWarmAdminPage } from "@/components/app-shell/admin-prefetch";
import { AiProvidersPage } from "@/components/app-shell/ai-providers-page";
import {
  aiProvidersSearchSchema,
  DEFAULT_AI_PROVIDERS_SEARCH,
} from "@/components/app-shell/ai-providers-search";
import {
  getChatModelDefaultOptions,
  listChatProviderAdaptersOptions,
  listChatProvidersOptions,
} from "@/lib/hey-api/@tanstack/react-query.gen";

export const Route = createFileRoute("/_authenticated/admin/ai-providers")({
  validateSearch: aiProvidersSearchSchema,
  search: { middlewares: [stripSearchParams(DEFAULT_AI_PROVIDERS_SEARCH)] },
  loaderDeps: ({ search: { tab } }) => ({ tab }),
  loader: ({ context: { queryClient }, deps: { tab } }) => {
    if (tab !== "models" || !mayWarmAdminPage(queryClient, "aiProviders")) return;
    void queryClient.prefetchQuery({ ...listChatProvidersOptions(), retry: false });
    void queryClient.prefetchQuery({ ...listChatProviderAdaptersOptions(), retry: false });
    void queryClient.prefetchQuery({ ...getChatModelDefaultOptions(), retry: false });
  },
  component: AiProvidersPage,
});
