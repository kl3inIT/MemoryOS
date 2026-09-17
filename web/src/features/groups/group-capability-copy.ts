import type { GroupCapability } from "@/lib/hey-api/types.gen";

// Stable registry IDs select UI copy; arbitrary server text never becomes a translation key.
export const capabilityCopy: Record<GroupCapability["id"], { label: string; description: string }> =
  {
    MODELS_MANAGE: {
      label: "Manage models",
      description: "Configure Chat providers, credentials, models and access within the Tenant.",
    },
    AGENTS_CREATE: {
      label: "Create agents",
      description: "Create custom Chat agents and share the agents they own or edit.",
    },
    AGENTS_MANAGE: {
      label: "Manage agents",
      description:
        "Edit, share, publish, feature, restore and transfer every custom agent, edit the default agent, and administer agent labels and public prompt shortcuts within the Tenant.",
    },
    MCP_MANAGE: {
      label: "Manage MCP servers",
      description:
        "Register remote MCP servers, their authentication and OAuth clients, enable their tools and control Group access within the Tenant.",
    },
    SYSTEM_ADMIN: {
      label: "Administrator access",
      description:
        "All product capabilities, including search, identity, user, group, Source, and model administration.",
    },
    SYSTEM_BASIC: {
      label: "Basic access",
      description:
        "Search and read eligible document passages, read and write own chats, plus reserved image generation and LLM gateway rights. Does not grant administrative capabilities.",
    },
    SEARCH_READ: {
      label: "Search documents",
      description:
        "Search and read eligible document passages, subject to Source visibility and document ACLs. Derived from Basic access; cannot be granted directly.",
    },
    CHAT_READ: {
      label: "Read chats",
      description:
        "Read own and shared conversations and follow replies. Derived from Basic access; cannot be granted directly.",
    },
    CHAT_WRITE: {
      label: "Write chats",
      description:
        "Start conversations, send, edit, regenerate and stop replies. Derived from Basic access; cannot be granted directly.",
    },
    IMAGE_GENERATE: {
      label: "Generate images",
      description:
        "Reserved for upcoming image generation; not an available or enforced feature permission. Derived from Basic access; cannot be granted directly.",
    },
    LLM_GATEWAY_USE: {
      label: "Use LLM gateway",
      description:
        "Reserved for upcoming LLM gateway use; not an available or enforced feature permission. Derived from Basic access; cannot be granted directly.",
    },
    USERS_MANAGE: {
      label: "Manage users",
      description: "Issue invitations and activate or deactivate Tenant users.",
    },
    GROUPS_READ: {
      label: "View groups",
      description:
        "View Groups and their memberships. Derived from Manage groups or Administrator access; cannot be granted directly. Scoped managers can view only the Groups they manage.",
    },
    GROUPS_MANAGE: {
      label: "Manage groups",
      description:
        "Create Groups and manage ordinary Groups. Scoped managers can rename their Groups, manage existing members, and delegate peer managers without global administration.",
    },
    SOURCES_READ: {
      label: "View Sources",
      description:
        "Globally view Source configuration and operation history. Derived from Manage Sources or Administrator access; cannot be granted directly. Scoped managers can view public, member-associated, or own nonpublic groupless Sources.",
    },
    SOURCES_MANAGE: {
      label: "Manage Sources",
      description:
        "Globally view and create Sources, edit Group associations, upload content, reindex, remove Source items, and delete Sources. Scoped managers can manage nonpublic Sources whose Groups they all manage, or their own nonpublic groupless Sources.",
    },
    SOURCES_DELETE: {
      label: "Delete Sources",
      description:
        "Globally remove Source items or delete Sources. Derived from Manage Sources or Administrator access; cannot be granted directly or exercised by scoped managers.",
    },
  };
