import { expect, test } from "@playwright/test";
import type { CurrentIdentity, UserListItem } from "@/lib/hey-api/types.gen";

const owner: CurrentIdentity = {
  actorId: "7b9f56d0-3026-4d2d-8e5f-1d6af6da93a1",
  authorizationVersion: 1,
  tenant: { displayName: "MemoryOS", role: "OWNER" },
  capabilities: ["USERS_MANAGE", "SOURCES_READ", "SOURCES_MANAGE"],
  scopedCapabilities: [],
};

const invitations: UserListItem[] = [
  ["75c4e810-e1f2-45cb-9480-8e713a934bca", "first@example.com"],
  ["a4f73975-b9a2-4b80-826d-70533d97db3e", "second@example.com"],
].map(([invitationId, email]) => ({
  actorId: null,
  invitationId,
  displayName: null,
  email,
  emailVerified: null,
  profileIssuer: null,
  role: null,
  status: "INVITED",
  accountType: null,
  groups: [],
  invitationExpiresAt: "2026-09-20T10:00:00Z",
}));

test("serializes one-time recovery issuance across invitation rows and the create action", async ({
  page,
}) => {
  const firstRotation = Promise.withResolvers<void>();
  const releaseFirstRotation = Promise.withResolvers<void>();
  const rotatedIds: string[] = [];
  const entries: UserListItem[] = [
    {
      actorId: owner.actorId,
      invitationId: null,
      displayName: "Alex Morgan",
      email: "alex@example.com",
      emailVerified: true,
      profileIssuer: "https://identity.example.com",
      role: "OWNER",
      status: "ACTIVE",
      invitationExpiresAt: null,
      accountType: "STANDARD",
      groups: [],
    },
    ...invitations,
  ];

  await page.route("**/api/identity/me", (route) => route.fulfill({ json: owner }));
  await page.route("**/api/users?*", (route) =>
    route.fulfill({
      json: {
        items: entries,
        page: 0,
        size: 20,
        totalItems: entries.length,
        totalPages: 1,
        counts: { active: 1, inactive: 0, invited: 2 },
      },
    }),
  );
  await page.route("**/api/invitations/*/rotate", async (route) => {
    const id = new URL(route.request().url()).pathname.split("/").at(-2);
    const entry = invitations.find((invitation) => invitation.invitationId === id);
    if (!entry || !id || route.request().method() !== "POST") {
      await route.fulfill({ status: 404 });
      return;
    }
    rotatedIds.push(id);
    if (rotatedIds.length === 1) {
      firstRotation.resolve();
      await releaseFirstRotation.promise;
    }
    await route.fulfill({
      json: {
        invitation: {
          id,
          email: entry.email,
          status: "PENDING",
          createdAt: "2026-09-06T10:00:00Z",
          expiresAt: entry.invitationExpiresAt,
          acceptedActorId: null,
          acceptedAt: null,
          revokedAt: null,
        },
        invitationUrl: `/invite/recovery-${id}`,
        delivery: "RECOVERY_LINK_ONLY",
      },
    });
  });

  await page.goto("/admin/users");
  await page.getByRole("button", { name: "Actions for first@example.com" }).click();
  await page.getByRole("button", { name: "Rotate recovery link" }).click();
  await firstRotation.promise;
  await expect(page.getByRole("button", { name: "Invite member" })).toBeDisabled();
  await page.getByRole("button", { name: "Actions for second@example.com" }).click();
  await expect(page.getByRole("button", { name: "Rotate recovery link" })).toBeDisabled();

  releaseFirstRotation.resolve();
  await expect(page.getByRole("textbox", { name: "Secure invitation link" })).toHaveValue(
    new RegExp(`/invite/recovery-${invitations[0].invitationId}$`),
  );
  await page.getByRole("button", { name: "Done" }).click();
  await expect(page.getByRole("button", { name: "Actions for first@example.com" })).toBeFocused();
  await expect(page.getByRole("textbox", { name: "Secure invitation link" })).toHaveCount(0);

  await page.getByRole("button", { name: "Actions for second@example.com" }).click();
  await page.getByRole("button", { name: "Rotate recovery link" }).click();
  await expect(page.getByRole("textbox", { name: "Secure invitation link" })).toHaveValue(
    new RegExp(`/invite/recovery-${invitations[1].invitationId}$`),
  );
  expect(rotatedIds).toEqual(invitations.map((invitation) => invitation.invitationId));
});
