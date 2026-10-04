/**
 * The tabs of a meeting, as its address names them. Kept apart from the page: the route validates the address
 * before the page's code is loaded.
 */
export const MEETING_PANES = ["summary", "actions", "decisions", "transcript", "notes"] as const;
export type MeetingPane = (typeof MEETING_PANES)[number];
