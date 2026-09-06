import { z } from "zod";
import type { ListUsersData } from "@/lib/hey-api/types.gen";

export type UsersQueryParameters = NonNullable<ListUsersData["query"]>;
export type UserStatusFilter = NonNullable<UsersQueryParameters["status"]>;
export type UserRoleFilter = NonNullable<UsersQueryParameters["role"]>;
export type UsersSort = NonNullable<UsersQueryParameters["sort"]>;

const optionalSearch = z
  .preprocess((value) => {
    if (typeof value !== "string") return undefined;
    const normalized = value.trim();
    return normalized || undefined;
  }, z.string().max(200).optional())
  .catch(undefined);

const optionalGroupId = z
  .preprocess(
    (value) => (typeof value === "string" && value ? value : undefined),
    z.uuid().optional(),
  )
  .catch(undefined);

export const usersSearchSchema = z.object({
  search: optionalSearch,
  status: z.enum(["ACTIVE", "INACTIVE", "INVITED"]).optional().catch(undefined),
  role: z.enum(["OWNER", "MEMBER"]).optional().catch(undefined),
  groupId: optionalGroupId,
  sort: z
    .enum([
      "NAME_ASC",
      "NAME_DESC",
      "EMAIL_ASC",
      "EMAIL_DESC",
      "STATUS_ASC",
      "STATUS_DESC",
      "ROLE_ASC",
      "ROLE_DESC",
    ])
    .catch("NAME_ASC"),
  page: z.coerce.number().int().min(0).catch(0),
  size: z.coerce
    .number()
    .pipe(z.union([z.literal(20), z.literal(50), z.literal(100)]))
    .catch(20),
});

export type UsersSearch = z.output<typeof usersSearchSchema>;

export function usersQuery(search: UsersSearch): UsersQueryParameters {
  return {
    search: search.search,
    status: search.status,
    role: search.role,
    groupId: search.groupId,
    sort: search.sort,
    page: search.page,
    size: search.size,
  };
}
