import { use, useLayoutEffect, type ReactNode } from "react";
import { createPortal } from "react-dom";
import { AppShellHeaderSlot, ShellBarTitleSetter } from "./app-shell-header-slot";

type AppShellHeaderProps = {
  title: string;
  actions?: ReactNode;
  /**
   * The page names itself in a `PageHeader`, so from `md` up the shell header steps aside rather than repeat the
   * title, as it does on administration and settings pages.
   */
  pageHeader?: boolean;
  /** The page has no heading of its own, so this title is its `h1`. */
  heading?: boolean;
};

/** The title and actions of the open page, as the shell header shows them. */
export function AppShellHeaderContent({
  title,
  actions,
  pageHeader = false,
  heading = false,
}: AppShellHeaderProps) {
  const Title = heading ? "h1" : "span";
  return (
    <>
      <Title
        data-page-header={pageHeader || undefined}
        title={title}
        className="min-w-0 flex-1 truncate font-main-ui-action text-content-primary md:max-w-xl"
      >
        {title}
      </Title>
      <div className="ml-auto flex shrink-0 items-center">{actions}</div>
    </>
  );
}

/**
 * Puts a page's title and actions into the shell header. The shell stays mounted across navigation, so a page
 * declares its header where it renders instead of wrapping itself in the shell; outside the shell it renders nothing.
 */
export function AppShellHeader(props: AppShellHeaderProps) {
  const slot = use(AppShellHeaderSlot);
  const setBarTitle = use(ShellBarTitleSetter);
  const barTitle = props.pageHeader ? props.title : undefined;
  // Before paint, so the page header never shows a title the bar already carries.
  useLayoutEffect(() => {
    setBarTitle?.(barTitle);
    return () => setBarTitle?.(undefined);
  }, [setBarTitle, barTitle]);
  return slot ? createPortal(<AppShellHeaderContent {...props} />, slot) : null;
}
