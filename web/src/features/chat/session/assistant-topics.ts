import { useQuery } from "@tanstack/react-query";
import type { ThreadTimeGroup } from "@/components/assistant-ui/elements/thread-list";
import { formatUiDate } from "@/i18n/format";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { listChatAssistantsOptions } from "@/lib/hey-api/@tanstack/react-query.gen";
import { personasOptions } from "@/features/chat/chat-personas-api";

/** What a face needs: the assistant, its seed and whether it has an uploaded image. */
export type AssistantFace = { id: string; avatarSeed: string; hasAvatar: boolean; name: string };

/**
 * The sidebar's assistants: the built-in one first, then the pins in order. A topic or a running reply refreshes
 * them while it runs, so the row's preview and its ring follow the answer; nothing is polled when nothing runs.
 */
export function useSidebarAssistants() {
  return useQuery({
    ...listChatAssistantsOptions(),
    refetchInterval: (query) =>
      query.state.data?.some((assistant) => assistant.latestTopic?.replyRunning) ? 4000 : false,
  });
}

/**
 * The face of each assistant a topic can belong to: the sidebar's assistants, which keep the ones no longer usable,
 * and every assistant the person may use. An assistant found in neither draws a face from its id.
 */
export function useAssistantFaces() {
  const assistants = useQuery(listChatAssistantsOptions());
  const personas = useQuery(personasOptions());
  const faces = new Map<string, AssistantFace>();
  for (const persona of personas.data ?? []) faces.set(persona.id, persona);
  for (const assistant of assistants.data ?? []) faces.set(assistant.id, assistant);
  return (personaId: string): AssistantFace =>
    faces.get(personaId) ?? { id: personaId, avatarSeed: personaId, hasAvatar: false, name: "" };
}

/** The heading of a time bucket: named periods, then the month or the year. */
export function useThreadGroupLabel() {
  const ui = useAppTranslation();
  return (group: ThreadTimeGroup) => {
    switch (group.kind) {
      case "today":
        return ui("Hôm nay");
      case "yesterday":
        return ui("Hôm qua");
      case "week":
        return ui("7 ngày qua");
      case "thisMonth":
        return ui("Tháng này");
      case "month": {
        const month = formatUiDate(group.start, { month: "long" });
        return month.charAt(0).toLocaleUpperCase() + month.slice(1);
      }
      case "year":
        return formatUiDate(group.start, { year: "numeric" });
    }
  };
}

/**
 * A short time for a row outside a dated group: the hour today, "Hôm qua", the weekday this week, the day this year,
 * the day and year before.
 */
export function useTopicTime() {
  const ui = useAppTranslation();
  return (value: string, now = new Date()) => {
    const date = new Date(value);
    const day = (offset: number) =>
      new Date(now.getFullYear(), now.getMonth(), now.getDate() + offset);
    if (date >= day(0)) return formatUiDate(date, { hour: "2-digit", minute: "2-digit" });
    if (date >= day(-1)) return ui("Hôm qua");
    if (date >= day(-6)) return formatUiDate(date, { weekday: "short" });
    return formatUiDate(date, {
      day: "numeric",
      month: "numeric",
      ...(date.getFullYear() === now.getFullYear() ? {} : { year: "2-digit" }),
    });
  };
}
