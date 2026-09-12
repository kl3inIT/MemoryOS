import type { GroupCapability } from "@/lib/hey-api/types.gen";

// Stable registry IDs select UI copy; arbitrary server text never becomes a translation key.
export const capabilityCopy: Record<GroupCapability["id"], { label: string; description: string }> =
  {
    MODELS_MANAGE: {
      label: "Manage models",
      description: "Configure Chat providers, credentials, models and access within the Tenant.",
    },
    IAM_ADMIN: {
      label: "IAM administration",
      description: "Full identity, user, group, and Source administration.",
    },
    USERS_MANAGE: {
      label: "Manage users",
      description: "Issue invitations and activate or deactivate Tenant users.",
    },
    GROUPS_READ: { label: "View groups", description: "View Groups and their memberships." },
    GROUPS_MANAGE: {
      label: "Manage groups",
      description: "Create Groups and manage ordinary Group memberships.",
    },
    SOURCES_READ: {
      label: "View Sources",
      description: "Globally view Source configuration and operation history.",
    },
    SOURCES_MANAGE: {
      label: "Manage Sources",
      description:
        "Globally create Sources, edit Group associations, upload content, and reindex. Scoped managers can upload or reindex only associated Sources without this grant.",
    },
    SOURCES_DELETE: {
      label: "Delete Sources",
      description:
        "Globally view and remove Source items or delete Sources, without upload or management access.",
    },
  };
