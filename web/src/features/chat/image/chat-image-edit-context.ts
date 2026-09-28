import { createContext } from "react";

/** Present only where the conversation can send; turns image mode on for an image edit. */
export const ChatImageEditContext = createContext<{ enableImages: () => void } | null>(null);
