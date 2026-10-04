/** A dialog or a confirmation open over the page; it keeps the keyboard to itself. */
export const OPEN_DIALOG = '[role="dialog"], [role="alertdialog"]';

/** Whether a key was pressed in a place that takes text. */
export function typing(target: EventTarget | null) {
  return (
    target instanceof HTMLElement &&
    (target.isContentEditable || target.matches("input, textarea, select"))
  );
}
