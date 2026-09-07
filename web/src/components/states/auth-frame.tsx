import type { ReactNode } from "react";
import { Brand } from "@/components/brand";

export function AuthFrame({ children }: { children: ReactNode }) {
  return (
    <main className="flex min-h-dvh items-center justify-center bg-surface-canvas px-4 py-10 text-content-primary">
      <section className="w-full max-w-md rounded-2xl border border-border-subtle bg-surface-raised p-6 shadow-md sm:p-8">
        <Brand />
        <div className="mt-6">{children}</div>
      </section>
    </main>
  );
}
