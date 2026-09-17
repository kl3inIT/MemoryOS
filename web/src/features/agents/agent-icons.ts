import {
  BarChart3,
  Bot,
  BookOpen,
  Briefcase,
  Calculator,
  FileText,
  Landmark,
  Lightbulb,
  MessageSquare,
  Scale,
  Search,
  ShieldCheck,
  Users,
  type LucideIcon,
} from "lucide-react";
/** Icon choices offered by the agent editor; the server stores only the key. */
export const agentIcons: Record<string, LucideIcon> = {
  bot: Bot,
  chart: BarChart3,
  finance: Landmark,
  calculator: Calculator,
  people: Users,
  legal: Scale,
  document: FileText,
  book: BookOpen,
  briefcase: Briefcase,
  search: Search,
  idea: Lightbulb,
  shield: ShieldCheck,
  chat: MessageSquare,
};

const info = "bg-status-info-surface text-status-info-content";
const success = "bg-status-success-surface text-status-success-content";
const warning = "bg-status-warning-surface text-status-warning-content";
const neutral = "bg-surface-sunken text-content-secondary";

/** Soft topic tints so a gallery of agents scans by subject rather than reading every name. */
export const agentIconTones: Record<string, string> = {
  bot: neutral,
  chart: info,
  finance: success,
  calculator: success,
  people: warning,
  legal: neutral,
  document: info,
  book: warning,
  briefcase: neutral,
  search: info,
  idea: warning,
  shield: success,
  chat: info,
};
