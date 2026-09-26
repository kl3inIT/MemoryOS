import { createFileRoute } from "@tanstack/react-router";
import { usersQuery, usersSearchSchema } from "@/features/users/users-search";
import { UsersPage } from "@/features/users/users-page";
import { listUsersOptions } from "@/lib/hey-api/@tanstack/react-query.gen";

export const Route = createFileRoute("/_authenticated/admin/users")({
  validateSearch: usersSearchSchema,
  loaderDeps: ({ search }) => search,
  loader: ({ context: { queryClient }, deps }) => {
    void queryClient.prefetchQuery({
      ...listUsersOptions({ query: usersQuery(deps) }),
      retry: false,
    });
  },
  component: UsersPage,
});
