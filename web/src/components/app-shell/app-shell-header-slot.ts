import { createContext } from "react";

/** The element in the shell header where the open application page puts its title and actions. */
export const AppShellHeaderSlot = createContext<HTMLElement | null>(null);
