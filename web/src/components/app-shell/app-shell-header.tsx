import { use, type ReactNode } from "react";
import { createPortal } from "react-dom";
import { AppShellHeaderSlot } from "./app-shell-header-slot";

/** The title and actions of the open page, as the shell header shows them. */
export function AppShellHeaderContent({ title, actions }: { title: string; actions?: ReactNode }) {
  return (
    <>
      <span
        title={title}
        className="min-w-0 flex-1 truncate font-main-ui-body text-content-primary md:max-w-xl"
      >
        {title}
      </span>
      <div className="ml-auto flex shrink-0 items-center">{actions}</div>
    </>
  );
}

/**
 * Puts a page's title and actions into the shell header. The shell stays mounted across navigation, so a page
 * declares its header where it renders instead of wrapping itself in the shell; outside the shell it renders nothing.
 */
export function AppShellHeader(props: { title: string; actions?: ReactNode }) {
  const slot = use(AppShellHeaderSlot);
  return slot ? createPortal(<AppShellHeaderContent {...props} />, slot) : null;
}
