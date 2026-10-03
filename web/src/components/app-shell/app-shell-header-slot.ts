import { createContext } from "react";

/** The element in the shell header where the open application page puts its title and actions. */
export const AppShellHeaderSlot = createContext<HTMLElement | null>(null);

/** The title the shell bar shows below `md`, so a page header that would repeat it steps aside there. */
export const ShellBarTitle = createContext<string | undefined>(undefined);

/** How a page in the application area tells the shell which title its bar carries. */
export const ShellBarTitleSetter = createContext<((title: string | undefined) => void) | null>(
  null,
);
