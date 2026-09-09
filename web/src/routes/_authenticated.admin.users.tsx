import { createFileRoute } from "@tanstack/react-router";
import { usersSearchSchema } from "@/features/users/users-search";
import { UsersPage } from "@/features/users/users-page";

export const Route = createFileRoute("/_authenticated/admin/users")({
  validateSearch: usersSearchSchema,
  component: UsersPage,
});
