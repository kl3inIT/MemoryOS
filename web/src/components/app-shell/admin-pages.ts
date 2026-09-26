import { useMatchRoute, type LinkProps } from "@tanstack/react-router";
import {
  AudioLines,
  Blocks,
  Bot,
  CloudUpload,
  Globe,
  ImageIcon,
  KeyRound,
  Library,
  MessagesSquare,
  Plug,
  ReceiptText,
  ScanSearch,
  ScrollText,
  SquareTerminal,
  Sparkles,
  User,
  Users,
  type LucideIcon,
} from "lucide-react";
import { appText, type AppText } from "@/i18n/app-text";
import type { AdminAuthority } from "@/features/identity/application-session-context";

export type AdminPage =
  | "chatHistory"
  | "sources"
  | "addSource"
  | "documentSets"
  | "users"
  | "groups"
  | "web"
  | "voice"
  | "images"
  | "interpreter"
  | "providers"
  | "models"
  | "searchSettings"
  | "mcp"
  | "agents"
  | "costs"
  | "audit";

export type AdminGroup = "configuration" | "agents" | "knowledge" | "tenant" | "monitoring";

export type AdminPageEntry = {
  id: AdminPage;
  to: LinkProps["to"];
  /** The sidebar link. */
  label: AppText;
  /** The page header. */
  title: AppText;
  icon: LucideIcon;
  group: AdminGroup;
  /** Who sees the link and may open the page. */
  visible: (authority: AdminAuthority) => boolean;
  /** The page owns the routes below its path too. */
  fuzzy?: boolean;
};

/** Sidebar sections, in the order the sidebar shows them. */
export const adminGroups: readonly { id: AdminGroup; label: AppText }[] = [
  { id: "configuration", label: appText("Configuration") },
  { id: "agents", label: appText("Trợ lý và công cụ") },
  { id: "knowledge", label: appText("Documents & Knowledge") },
  { id: "tenant", label: appText("Tenant") },
  { id: "monitoring", label: appText("Monitoring") },
];

const manageModels = (authority: AdminAuthority) => authority.canManageModels;
const readSources = (authority: AdminAuthority) => authority.canReadSources;

/** Every administration page, in sidebar order within its section. */
export const adminPages: readonly AdminPageEntry[] = [
  {
    id: "models",
    to: "/admin/models",
    label: appText("Mô hình"),
    title: appText("Models"),
    icon: Sparkles,
    group: "configuration",
    visible: manageModels,
  },
  {
    id: "web",
    to: "/admin/web-search",
    label: appText("Tìm kiếm Web"),
    title: appText("Tìm kiếm Web"),
    icon: Globe,
    group: "configuration",
    visible: manageModels,
  },
  {
    id: "voice",
    to: "/admin/voice",
    label: appText("Giọng nói"),
    title: appText("Giọng nói"),
    icon: AudioLines,
    group: "configuration",
    visible: manageModels,
  },
  {
    id: "images",
    to: "/admin/image-generation",
    label: appText("Tạo ảnh"),
    title: appText("Tạo ảnh"),
    icon: ImageIcon,
    group: "configuration",
    visible: manageModels,
  },
  {
    id: "interpreter",
    to: "/admin/code-interpreter",
    label: appText("Code Interpreter"),
    title: appText("Code Interpreter"),
    icon: SquareTerminal,
    group: "configuration",
    visible: manageModels,
  },
  {
    id: "agents",
    to: "/admin/agents",
    label: appText("Quản lý trợ lý"),
    title: appText("Quản lý trợ lý"),
    icon: Bot,
    group: "agents",
    visible: (authority) => authority.canManageAgents,
  },
  {
    id: "mcp",
    to: "/admin/mcp",
    label: appText("Máy chủ MCP"),
    title: appText("Máy chủ MCP"),
    icon: Blocks,
    group: "agents",
    visible: (authority) => authority.canManageMcp,
  },
  {
    id: "sources",
    to: "/admin",
    label: appText("Existing sources"),
    title: appText("Sources"),
    icon: Plug,
    group: "knowledge",
    visible: readSources,
  },
  {
    id: "addSource",
    to: "/admin/sources/new",
    label: appText("Add a source"),
    title: appText("Add a source"),
    icon: CloudUpload,
    group: "knowledge",
    visible: readSources,
    fuzzy: true,
  },
  {
    id: "documentSets",
    to: "/admin/document-sets",
    label: appText("Bộ tài liệu"),
    title: appText("Bộ tài liệu"),
    icon: Library,
    group: "knowledge",
    visible: readSources,
    fuzzy: true,
  },
  {
    id: "searchSettings",
    to: "/admin/search-settings",
    label: appText("Cấu hình tìm kiếm"),
    title: appText("Cấu hình tìm kiếm"),
    icon: ScanSearch,
    group: "knowledge",
    visible: manageModels,
  },
  {
    id: "users",
    to: "/admin/users",
    label: appText("Users"),
    title: appText("Users"),
    icon: User,
    group: "tenant",
    visible: (authority) => authority.canManageUsers,
  },
  {
    id: "groups",
    to: "/admin/groups",
    label: appText("Groups"),
    title: appText("Groups"),
    icon: Users,
    group: "tenant",
    visible: (authority) => authority.canReadGroups,
    fuzzy: true,
  },
  {
    id: "providers",
    to: "/admin/identity-providers",
    label: appText("Sign-in providers"),
    title: appText("Sign-in providers"),
    icon: KeyRound,
    group: "tenant",
    visible: (authority) => authority.canManageProviders,
  },
  {
    id: "costs",
    to: "/admin/ai-costs",
    label: appText("AI costs"),
    title: appText("AI costs"),
    icon: ReceiptText,
    group: "monitoring",
    visible: manageModels,
  },
  {
    id: "chatHistory",
    to: "/admin/chat-history",
    label: appText("Conversation history"),
    title: appText("Conversation history"),
    icon: MessagesSquare,
    group: "monitoring",
    visible: (authority) => authority.canReadChatHistory,
  },
  {
    id: "audit",
    to: "/admin/audit",
    label: appText("Audit log"),
    title: appText("Audit log"),
    icon: ScrollText,
    group: "monitoring",
    visible: (authority) => authority.canReadAudit,
  },
];

export function adminPage(id: AdminPage) {
  const entry = adminPages.find((page) => page.id === id);
  if (!entry) throw new Error(`Administration page ${id} is not declared`);
  return entry;
}

/** Where the administration entry lands: the first page of this order the person may open. */
const entryOrder: readonly AdminPage[] = [
  "sources",
  "groups",
  "users",
  "providers",
  "models",
  "mcp",
  "agents",
  "audit",
  "chatHistory",
];

export function adminEntryPage(authority: AdminAuthority) {
  const id = entryOrder.find((candidate) => adminPage(candidate).visible(authority)) ?? "audit";
  return adminPage(id);
}

/** The administration page the current route belongs to; Sources owns every path no other page claims. */
export function useCurrentAdminPage() {
  const matchRoute = useMatchRoute();
  return (
    adminPages.find(
      (page) => page.id !== "sources" && matchRoute({ to: page.to, fuzzy: page.fuzzy }) !== false,
    ) ?? adminPage("sources")
  );
}
