import { z } from "zod";
import type { ListInvitationsData } from "@/lib/hey-api/types.gen";

export type InvitationListQuery = NonNullable<ListInvitationsData["query"]>;
export type InvitationStatusFilter = NonNullable<InvitationListQuery["status"]>;
export type InvitationSort = NonNullable<InvitationListQuery["sort"]>;

const optionalEmail = z
  .preprocess((value) => {
    if (typeof value !== "string") return undefined;
    const normalized = value.trim().toLowerCase();
    return normalized || undefined;
  }, z.string().max(254).optional())
  .catch(undefined);

export const invitationListSearchSchema = z.object({
  status: z.enum(["PENDING", "ACCEPTED", "EXPIRED", "REVOKED"]).optional().catch(undefined),
  email: optionalEmail,
  sort: z
    .enum(["CREATED_AT_DESC", "CREATED_AT_ASC", "EMAIL_ASC", "EMAIL_DESC"])
    .catch("CREATED_AT_DESC"),
  page: z.coerce.number().int().min(0).catch(0),
  size: z.coerce
    .number()
    .pipe(z.union([z.literal(20), z.literal(50), z.literal(100)]))
    .catch(20),
});

export type InvitationListSearch = z.output<typeof invitationListSearchSchema>;

export const defaultInvitationListSearch: InvitationListSearch = {
  sort: "CREATED_AT_DESC",
  page: 0,
  size: 20,
};

export function invitationListQuery(search: InvitationListSearch): InvitationListQuery {
  return {
    status: search.status,
    email: search.email,
    sort: search.sort,
    page: search.page,
    size: search.size,
  };
}
