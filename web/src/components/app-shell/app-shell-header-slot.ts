import { createContext } from "react";

/** The element in the shell header where the open application page puts its title and actions. */
export const AppShellHeaderSlot = createContext<HTMLElement | null>(null);

/** The title the shell bar shows below `md`, so a page header that would repeat it steps aside there. */
export const ShellBarTitle = createContext<string | undefined>(undefined);

/** How a page in the application area tells the shell which title its bar carries. */
export const ShellBarTitleSetter = createContext<((title: string | undefined) => void) | null>(
  null,
);

/**
 * How a page header in the administration or settings area gives the bar its own title, so a create or detail
 * page is named once on a phone as a list page is. Absent in the application area, where the page fills the bar.
 */
export const ShellBarTitleClaim = createContext<((title: string | undefined) => void) | null>(null);
