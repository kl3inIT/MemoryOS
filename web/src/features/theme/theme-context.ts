import { createContext, useContext } from "react";

export type Theme = "light" | "dark";
export type ThemePreference = Theme | "system";

export type ThemeContextValue = {
  preference: ThemePreference;
  resolvedTheme: Theme;
  setTheme: (theme: ThemePreference) => void;
};

export const ThemeContext = createContext<ThemeContextValue | null>(null);

export function useTheme() {
  const context = useContext(ThemeContext);
  if (!context) {
    throw new Error("useTheme must be used inside ThemeProvider");
  }
  return context;
}
