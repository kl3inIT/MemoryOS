import { useRef } from "react";

/**
 * Keeps the focus ring off the trigger of a menu or popover that a pointer opened. Closing hands focus back to the
 * trigger by script, and the browser then draws the ring as if the keyboard had reached it. A pointer-opened overlay
 * leaves focus where it is; a keyboard-opened one still returns it, so the ring stays for the keyboard.
 *
 * Spread `trigger` on the trigger element and `content` on the `DropdownMenuContent` or `PopoverContent`.
 */
export function usePointerOpenedTrigger() {
  const byPointer = useRef(false);
  return {
    trigger: {
      onPointerDown: () => {
        byPointer.current = true;
      },
    },
    content: {
      onCloseAutoFocus: (event: Event) => {
        if (!byPointer.current) return;
        byPointer.current = false;
        event.preventDefault();
      },
    },
  };
}
