import { Menu } from "lucide-react";
import { useRef } from "react";
import { ActionLink } from "@/components/action-link";
import { BrandMark } from "@/components/brand-mark";
import { contact, navigation } from "@/content";

function Header() {
  const mobileMenu = useRef<HTMLDetailsElement>(null);
  const closeMobileMenu = () => mobileMenu.current?.removeAttribute("open");

  return (
    <header className="sticky top-0 z-40 border-b border-border-subtle bg-surface-base/90 px-[var(--page-gutter)] backdrop-blur-md">
      <a
        href="#main"
        className="sr-only rounded-lg bg-surface-raised px-3 py-2 font-main-ui-action focus:not-sr-only focus:absolute focus:top-3 focus:left-3"
      >
        Skip to content
      </a>
      <div className="mx-auto flex h-16 max-w-[var(--page-width-wide)] items-center gap-3">
        <a href="/" className="mr-auto flex items-center gap-2.5 rounded-md">
          <BrandMark className="size-7" />
          <span className="font-heading-h3 text-content-primary">MemoryOS</span>
          <span className="hidden font-main-ui-body text-content-muted sm:inline">by Vanda</span>
        </a>
        <nav aria-label="Primary" className="hidden md:block">
          <ul className="flex items-center gap-1">
            {navigation.map((item) => (
              <li key={item.href}>
                <ActionLink href={item.href} prominence="tertiary">
                  {item.label}
                </ActionLink>
              </li>
            ))}
          </ul>
        </nav>
        <ActionLink href={contact.href}>{contact.label}</ActionLink>
        <details ref={mobileMenu} className="relative md:hidden">
          <summary className="flex size-11 cursor-pointer list-none items-center justify-center rounded-lg text-content-secondary hover:bg-surface-canvas [&::-webkit-details-marker]:hidden">
            <Menu aria-hidden="true" className="size-5" />
            <span className="sr-only">Menu</span>
          </summary>
          <nav
            aria-label="Primary"
            className="absolute top-full right-0 mt-2 w-56 rounded-xl border border-border-subtle bg-surface-raised p-2 shadow-md"
          >
            <ul>
              {navigation.map((item) => (
                <li key={item.href}>
                  <a
                    href={item.href}
                    onClick={closeMobileMenu}
                    className="block rounded-lg px-3 py-2.5 font-main-ui-action text-content-secondary hover:bg-surface-canvas hover:text-content-primary"
                  >
                    {item.label}
                  </a>
                </li>
              ))}
            </ul>
          </nav>
        </details>
      </div>
    </header>
  );
}

export { Header };
